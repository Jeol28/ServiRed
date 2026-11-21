# Scripts de operación

Se corren desde la raíz del repositorio, con el compose arriba. Leen las credenciales de
`.env`, o del archivo que indique `SERVIRED_ENV`.

## Colas de mensajes fallidos (R2): `dlq.py`

Un evento llega a `<cola>.dlq` cuando su consumidor no pudo procesarlo: por una falla
permanente (datos inválidos) o por una falla transitoria que agotó sus reintentos. La
alerta `MensajesEnColaDeFallidos` avisa cuando hay alguno.

```bash
python3 infra/operacion/dlq.py listar
python3 infra/operacion/dlq.py ver atencion.eventos-reportes      # sin consumirlos
python3 infra/operacion/dlq.py reprocesar atencion.eventos-reportes
python3 infra/operacion/dlq.py descartar atencion.eventos-reportes --confirmar
```

- **`ver`:** muestra el tipo, el `idEvento`, el `correlationId` (para buscar la causa en
  los logs) y cuándo y por qué se rechazó cada mensaje.
- **`reprocesar`:** úselo una vez corregida la causa. Devuelve los mensajes a su cola con
  un shovel que confirma cada uno, así que no se pierde ninguno. Los consumidores son
  idempotentes, así que reprocesar un evento ya aplicado no lo duplica.
- **`descartar`:** guarda primero los mensajes en un archivo JSON y después los consume con
  confirmación. Si llega uno mientras tanto, también lo agrega a la copia.
- **Ojo:** las DLQ tienen la política `dlq-sin-limite-de-entregas`. Sin ella, cada lectura
  desde la consola de RabbitMQ («Get messages») cuenta como una entrega, y a la lectura 21
  el mensaje **se borra**. `dlq.py` se niega a leer una DLQ sin esa política.

## Vista de Indicadores (R14): `reconstruir-indicadores.py`

La vista de Indicadores se arma con los eventos. Si se perdieron eventos o la proyección
falló, quedan desalineados los indicadores, los mapas y los datos abiertos.

```bash
python3 infra/operacion/reconstruir-indicadores.py            # verificar: código 2 si hay diferencias
python3 infra/operacion/reconstruir-indicadores.py --aplicar  # reconstruir desde el origen
```

- **Origen:** reportes, votos que cuentan y casos.
- **Cómo lo hace:** lo ejecuta Persistencia (operación `reconstruirVistaIndicadores`, solo
  para el servicio Indicadores) en una transacción.
- **Qué informa:** por columna, cuántas filas faltan, sobran o difieren.
