# ADR-24: Operación de las colas de fallidos, reconstrucción de Indicadores y prueba de carga 10×

## Estado
Aceptada.

## Contexto
Hay tres riesgos que atacar con herramientas de operación y una prueba:

- **R2:** mensajes que quedan en las colas de fallidos (`<cola>.dlq`) sin que nadie los
  revise ni los reprocese.
- **R14:** la vista de lectura de Indicadores, que es una proyección de eventos, se
  desalinea de los datos de origen.
- **R19:** falta demostrar RNF-E2, «soportar incrementos de tráfico de al menos diez veces
  la carga promedio en una zona afectada sin pérdida de reportes aceptados».

## Decisión

### R2: `infra/operacion/dlq.py`
Tiene cuatro comandos: `listar`, `ver` (no consume), `reprocesar` y `descartar`.

- **`reprocesar`** usa un shovel dinámico con `ack-mode on-confirm` y
  `src-delete-after queue-length`. Cada mensaje sale de la DLQ solo cuando la cola
  principal lo confirmó, así que no se pierde ninguno.
- **`descartar`** exige `--confirmar` y antes guarda una copia en JSON.
- **Hallazgo:** en RabbitMQ 4.1 una cola quorum tiene por defecto un límite de 20 entregas.
  Leer un mensaje desde la consola («Get messages», con requeue) cuenta como entrega, y a
  la lectura 21 el mensaje **se borra sin aviso**. Se comprobó.
- **Corrección:** la topología agrega la política `dlq-sin-limite-de-entregas`
  (`delivery-limit: -1`, solo para `\.dlq$`). `verificar-topologia.py` la exige, y
  `dlq.py` se niega a leer una DLQ que no la tenga.
- Se habilitó el plugin `rabbitmq_shovel`.

### R14: reconstrucción de la vista de Indicadores
Persistencia ofrece la operación `reconstruirVistaIndicadores`, que solo puede llamar el
servicio Indicadores, y `infra/operacion/reconstruir-indicadores.py` la invoca.

- **Origen:** recalcula cada fila desde los reportes, los votos que cuentan (ADR-22) y los
  casos.
- **Verificar:** informa por columna cuántas filas faltan, sobran o difieren, y devuelve el
  código 2 si hay diferencias, lo que sirve para una tarea programada.
- **`--aplicar`:** reescribe la vista en una transacción, con
  `LOCK ... SHARE ROW EXCLUSIVE`, mientras la proyección espera.

### R19: `pruebas/carga/carga-10x.py`
Simula un corte masivo en Bogotá (escenario E7):

- **Ciudadanos:** muchas cuentas distintas, cada una por debajo de su límite (ADR-22). Cada
  una crea su reporte y luego consulta el reporte y el mapa público.
- **Carga de lazo abierto:** el ritmo no baja aunque el sistema se demore.
- **Reintentos como la app:** ante un error de red o un 5xx se reintenta con el mismo
  `X-Correlation-Id`.
- **Qué comprueba para cada 201:**
  - está en la base, publicó `reporte.creado`, abrió su caso, llegó a la vista y quedó en
    Auditoría;
  - no hay duplicados y las DLQ no crecieron;
  - RNF-R1 (p95 ≤ 2 s) y RNF-R3 (visible para el operador en ≤ 10 s).
- **Fotos (`--fotos`, por defecto 0,5):** la mitad de los ciudadanos adjunta una foto como
  las de las apps: JPEG de 2560 × 1920 a calidad 85, de 0,9 a 1,3 MB, con EXIF de GPS y
  modelo del teléfono. Se comprueba que cada foto aceptada:
  - tiene su referencia en la base;
  - se descarga y es un JPEG;
  - llegó sin EXIF.

  También comprueba que ningún servicio se reinició ni se quedó sin memoria.
- **Seguridad:** se niega a correr contra Supabase. El plan gratuito es justo lo que no
  se debe saturar.

**Carga promedio supuesta:** 60 reportes por minuto en la zona. El SAD no fija la cifra:
`--promedio` la ajusta cuando haya el dato real de la operación.

## Resultado (compose local, 2026-11-18)

Pico de 10× = 10 reportes/s durante 120 s: 1200 reportes de 150 ciudadanos, más 2400
consultas. En la segunda corrida, además, 607 fotos (la tabla es de esa corrida).

| Comprobación | Resultado |
|---|---|
| Aceptados (201) | 1200 de 1200, sin reintentos |
| Perdidos | 0. Los 1200 en la base, con `reporte.creado` publicado, caso, fila en la vista y registro en Auditoría, 3 s después del fin del pico |
| Duplicados / DLQ | 0 / sin crecer |
| Creación (RNF-R1) | p50 7 ms · p95 13 ms · máx 37 ms |
| Fotos | 607 de 607 aceptadas, con referencia en la base, descargables y sin EXIF; subida p50 141 ms · p95 163 ms · máx 202 ms |
| Recursos en el pico | Reportes: hasta 124 % de CPU y 726 MiB de memoria, porque decodifica y vuelve a codificar cada foto. Persistencia, 490 MiB; gateway, 399 MiB; almacén, 458 MiB. Sin reinicios |
| Consultas | p95 7 ms, sin errores |
| Visible para el operador (RNF-R3) | p50 0,29 s · p95 0,51 s · máx 0,54 s |

## Consecuencias
- **Alcance del resultado:** vale para una réplica de cada servicio en un portátil, con la
  base local. Contra Supabase, con su latencia de red y su pool de 10 conexiones, los
  números cambian.
- **Memoria de Reportes:** los contenedores locales no tienen límite de memoria. En una
  máquina con poca RAM, el saneamiento de fotos de Reportes es lo primero que se debe
  vigilar: usó 726 MiB en el pico. La prueba no mide el techo del sistema, solo que 10× del promedio
  supuesto se atiende sin pérdida.
- **Preparación lenta:** toda la prueba sale de una sola IP y el inicio de sesión está
  limitado a 20 por minuto por IP, así que las 150 sesiones toman unos 7 minutos. Es el
  límite funcionando, no un problema del sistema.
- **Hallazgo de R14, corregido:** la primera verificación en la base local encontró 131
  de 259 filas sin el estado ni la prioridad del caso, y 117 sin empresa.
  - **Causa:** Atención abría el caso sin emitir un evento, así que la vista solo se
    enteraba cuando el caso cambiaba. Los mapas, los indicadores por empresa y las
    exportaciones a la SSPD dejaban fuera esos reportes, y dos exportaciones podían no
    coincidir.
  - **Corrección:** Atención publica `caso.actualizado` al abrir el caso, con estado
    `RECIBIDO`, prioridad y empresa, sin `estadoAnterior` ni `idActor`. Se reutiliza el
    contrato v1 sin cambiarlo.
  - **Por el outbox:** `abrirCaso` de Persistencia recibe el evento y lo guarda en la
    misma transacción, solo si el caso se insertó. Un reintento o una redelivery no
    publica una segunda apertura, y solo admite `caso.actualizado`.
  - **Consumidores:** Reportes ignora `RECIBIDO`, así que el ciudadano no ve cambios.
    Indicadores completa la fila; un evento de apertura tardío no pisa un cambio
    posterior, porque la vista compara `caso_en`. Auditoría lo registra como
    `CASO_ACTUALIZADO`.
  - **Datos anteriores:** los casos abiertos antes del cambio se reparan una vez con
    `reconstruir-indicadores.py --aplicar`, también en la VM. La e2e
    `apertura-de-caso.py` comprueba que después los reportes nuevos dejan la vista
    alineada.
