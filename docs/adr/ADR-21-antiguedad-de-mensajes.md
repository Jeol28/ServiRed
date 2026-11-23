# ADR-21: Antigüedad de los mensajes (RNF-R3)

## Estado
Aceptada.

## Contexto
RNF-R3 exige que un reporte aceptado sea visible para el operador en máximo 10
segundos. El reporte recorre este camino:

```
reporte guardado ─▶ outbox ─▶ relay ─▶ servired.eventos ─▶ atencion.eventos-reportes ─▶ Atención abre el caso
                    (espera 1)                              (espera 2)
```

Las alertas solo contaban mensajes: `OutboxAtascado` con más de 20 eventos sin
publicar y `ColaDeEventosAcumulada` con más de 500 en una cola. Eso no dice cuánto
lleva esperando un mensaje. Con un consumidor detenido, 30 mensajes pueden llevar
minutos en la cola, y con un consumidor rápido, 600 se procesan en un segundo.

Lo que importa es **la edad del mensaje más viejo**:

- **En el outbox** se mide exacta: `now() − min(creado_en)` de los eventos sin publicar.
- **En las colas, RabbitMQ no la da.** `head_message_timestamp` solo existe en las
  colas clásicas, y las nuestras son quorum (ADR-10). Se comprobó en RabbitMQ 4.1: la
  cola clásica informa la marca de tiempo y la quorum devuelve `null`. Tampoco se puede
  «espiar» la cabeza (`basic.get` y devolver el mensaje), porque eso aumenta su
  contador de entregas y con el límite de entregas puede terminar en la DLQ.

## Decisión

### Edad en el outbox
Persistencia publica `servired_outbox_mensaje_mas_viejo_segundos`: la edad del evento
guardado más viejo que aún no se ha publicado, o 0 si no hay ninguno. La consulta usa el
índice parcial `outbox_pendientes_idx`.

### Edad en las colas: mensaje sonda
- **Publicación:** Persistencia publica cada 2 s una sonda por el mismo exchange que los
  eventos. Lleva la routing key `sonda.v1` y la cabecera `x-sonda-publicada-ms` con su
  hora de publicación, y es un mensaje no persistente con cuerpo `{}`. La topología enruta
  `sonda.v1` a las cuatro colas consumidoras; Auditoría ya la recibía por su `#`.
- **Recepción:** cada consumidor, al recibir la sonda, guarda su hora de publicación y la
  confirma. La sonda no llega a la lógica de negocio ni al registro de auditoría.
- **Por qué funciona:** la cola es FIFO, así que todo lo publicado antes de la última
  sonda consumida ya salió. Por lo tanto, el mensaje más viejo pendiente tiene a lo sumo
  `ahora − publicación de esa sonda`.
- **La métrica:** cada consumidor publica esa cota como
  `servired_cola_mensaje_mas_viejo_segundos{cola}`.
  - Se calcula en cada raspado. Si la cola se atasca, la edad sigue creciendo aunque no
    lleguen sondas.
  - Antes de la primera sonda, la referencia es el arranque del proceso. Un consumidor que
    nunca consume ve crecer la edad.
  - La primera sonda reemplaza esa referencia aunque sea más vieja: así se mide una cola
    que ya estaba atrasada al arrancar.
  - Con varias réplicas, cada una publica su propia serie. Las reglas toman la menor por
    cola, que es la de la réplica que consumió más recientemente.
- **Error y otras métricas:** la cota tiene un error de hasta 2 s (el intervalo de la
  sonda). Además, `servired_cola_espera_sonda_segundos` registra cuánto esperó cada sonda,
  y `servired_sonda_ultima_publicacion_segundos` dice hace cuánto se publicó la última.

### Alertas
| Alerta | Condición | Severidad |
|---|---|---|
| `ReporteNoVisibleATiempo` (RNF-R3) | edad en el outbox + edad en `atencion.eventos-reportes` > 10 s durante 30 s | crítica |
| `MensajeViejoEnCola` | edad en otra cola > 60 s durante 1 min (esas colas no tienen el límite de 10 s) | media |
| `EventoViejoEnOutbox` | un evento lleva > 10 s sin publicarse durante 30 s | alta |

- **Guarda:** las tres exigen que la sonda se esté publicando (hace menos de 10 s). Si
  Persistencia está caída no se culpa a las colas, porque ya avisan `ServicioSinReplicas`
  y `CircuitoPersistenciaAbierto`.
- **Alertas anteriores:** `OutboxAtascado` y `ColaDeEventosAcumulada` se mantienen. El
  tamaño de la acumulación sigue siendo útil, solo que no basta.
- **Pruebas:** `infra/observabilidad/pruebas-reglas.yml` prueba las reglas con
  `promtool test rules`: que se disparen, que sumen outbox y cola, que no culpen a una
  réplica caída y que respeten la guarda de la sonda.

## Alternativas descartadas
- **Colas clásicas para tener `head_message_timestamp`:** se perdería la replicación y
  la seguridad de las quorum (ADR-10) solo para medir.
- **Leer la cabeza con `basic.get`:** modifica el mensaje (contador de entregas) y puede
  mandarlo a la DLQ.
- **Medir solo en el consumidor la edad de cada evento al recibirlo:** no ve un
  consumidor detenido, que es justo cuando más importa. La sonda sí, porque la edad crece
  en cada raspado.
- **Estimar con la ley de Little (largo de la cola / ritmo de consumo):** es una
  aproximación que falla con el consumidor detenido (ritmo 0).
- **Publicar la sonda a través del outbox:** mediría el camino completo con una sola
  métrica, pero escribiría en la tabla cada 2 s. La edad del outbox ya se mide exacta.

## Consecuencias
- **Tráfico:** cada cola recibe un mensaje pequeño cada 2 s por réplica de Persistencia
  (unos 43 000 al día). Si un consumidor está caído, sus sondas se acumulan, unas 1 800
  por hora, y se descartan en milisegundos al volver.
- **Ceguera con el consumidor caído:** no hay métrica de la cola, porque es el
  consumidor quien la publica. Para ese caso está `ServicioSinReplicas`.
- **Escala de medición:** la medida es de segundos (sonda cada 2 s, raspado cada 15 s,
  `for` de 30 s). Basta para un requisito de 10 s, pero la alerta llega unos 45 s
  después de que empieza el problema.
