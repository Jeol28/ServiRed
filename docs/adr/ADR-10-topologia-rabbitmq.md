# ADR-10 · Topología de RabbitMQ: reintentos y mensajes fallidos

**Estado:** aceptada · **Fecha:** 2026-11-14

## Decisión
Definida en `infra/rabbitmq/generar-definiciones.py` (genera `definiciones.json`).

```
servired.eventos (topic) ──patrón──▶ <cola>                 (quorum, durable)
<cola> ──rechazo definitivo──▶ servired.dlx (direct) ──▶ <cola>.dlq
consumidor ──falla transitoria──▶ <cola>.reintento           (TTL 10 s)
<cola>.reintento ──TTL vencido──▶ servired.reintentos (direct) ──▶ <cola>
```

| Cola | Eventos |
|---|---|
| `atencion.eventos-reportes` | reporte.creado, restablecimiento.registrado, validacion.registrada, denuncia.recibida |
| `reportes.eventos-casos` | caso.actualizado, caso.cerrado |
| `indicadores.eventos` | reporte.#, restablecimiento.#, validacion.#, caso.# |
| `auditoria.eventos` | # (todos) |

- Routing key `<agregado>.<evento>.v<N>`; una versión nueva se publica en paralelo.
- Colas **quorum** (replicables, recomendadas en RabbitMQ 4) y mensajes persistentes.
- El consumidor cuenta los intentos en una cabecera; al superar el máximo
  rechaza sin reencolar y el mensaje termina en la DLQ.

## Lección aprendida
Usar el exchange por defecto (`""`) como `x-dead-letter-exchange` de la cola de
reintento **descartaba los mensajes**: al importar las definiciones el valor
queda como lista vacía y la cola quorum lo trata como "sin DLX". Por eso existe
`servired.reintentos`. `infra/rabbitmq/verificar-topologia.py` lo comprueba.
