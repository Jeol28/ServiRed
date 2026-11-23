# ADR-33: Se retira la auditoría

## Estado
**Revertida** (2026-11-22). La auditoría vuelve tal como estaba: ADR-16, ADR-29 y ADR-31 siguen
vigentes y el SAD conserva RF-12 y RF-13.

- **Lo que queda de esta decisión:** la migración V18, que ya se aplicó y por eso no se borra.
- **V19** recrea el esquema `auditoria` (registro, segmentos y anclas) **vacío**: lo que borró
  V18 no se recupera y la cadena de huellas empieza de nuevo. Donde V18 no se llegó a aplicar,
  V18 borra el esquema y V19 lo crea vacío en el mismo arranque, así que el efecto es el mismo.
- **Se conserva de V18:** la protección de `reportes.recalculo_municipio` sigue en
  `reportes.prohibir_cambios()`. V19 devuelve el resumen `accion.auditada` a
  `reportes.recalcular_municipios`.

El resto de este documento describe la decisión que se revirtió.

## Contexto
El enunciado del proyecto no pide auditoría. El SAD de la Entrega 1 la había agregado por su
cuenta:
- **RF-12:** un registro de accesos, cambios de estado, validaciones, exportaciones y
  acciones administrativas.
- **RF-13:** las alertas de seguridad (fuerza bruta, fallos de acceso masivos, token
  reutilizado, exportaciones inusuales).
- **El objetivo** «Mantener auditoría verificable de accesos, decisiones y cambios de estado».
- **El microservicio** Auditoría (.NET).

Encima de eso se construyeron:
- la cadena de huellas;
- el evento `accion.auditada`, que publicaban Identidad, Atención, Indicadores y la
  migración V17;
- el archivado en segmentos (ADR-29);
- las anclas diarias en GitHub (ADR-31);
- una página del portal.

Todo eso es alcance que nadie pidió y que hay que operar: la cola `auditoria.eventos` recibía
todos los eventos, el esquema crecía y había secretos, tareas diarias y alertas propias.

## Decisión
Se retira la auditoría completa, también del SAD.

- **SAD:** salen RF-12, el objetivo de auditoría verificable y el microservicio, en el texto y
  en las figuras. El antiguo RF-13 pasa a ser **RF-12** y queda solo con las alertas
  operativas («Monitorear procesos críticos y generar alertas operativas»). Los ADR anteriores
  que citan RF-12 o RF-13 usan la numeración vieja.

- **Servicio:** se borra `servicios/auditoria`, junto con su ruta en el gateway (ahora
  `/api/v1/auditoria/**` responde 403) y su página en el portal de operación.
- **Evento:** `accion.auditada` sale del contrato (AsyncAPI, esquema, ejemplos y el enum de
  `tipo` del sobre). Los servicios ya no lo construyen.
  - Las operaciones de Persistencia sobre cuentas y moderación ya no reciben el arreglo
    `eventos`.
  - `caso.actualizado` y `caso.cerrado` no cambian: siguen yendo al outbox en la misma
    transacción.
- **Datos:** la migración V18 borra el esquema `auditoria` completo (`DROP SCHEMA ... CASCADE`),
  es decir, el registro, el índice de segmentos y las anclas. Es **irreversible**. Antes:
  - pasa los disparadores de solo inserción de `reportes.recalculo_municipio` a una función de
    su propio esquema (`reportes.prohibir_cambios()`);
  - recrea `reportes.recalcular_municipios` sin el resumen `accion.auditada`;
  - borra del outbox los `accion.auditada` que no se habían publicado.

  Las migraciones V9, V15, V16 y V17 se quedan: ya están aplicadas y Flyway valida su suma.
- **Fuera del sistema no se toca nada:** quedan los archivos que haya en el almacén bajo
  `auditoria/segmentos/` y la rama `anclas-auditoria` en GitHub. Se borran a mano si se quiere.
- **RabbitMQ:** la cola `auditoria.eventos`, con su reintento y su DLQ, sale de la topología.
  Importar definiciones no borra lo que ya existe en un broker en marcha, así que
  `aplicar-definiciones.sh` las elimina. Sin consumidor, la cola acumularía todo (escuchaba
  `#`).
- **Alertas:**
  - **Se van:** las de seguridad de RF-13 que detectaba Auditoría, y las de Prometheus que
    dependían de ella (`AlertaDeSeguridadDetectada`, inicios de sesión fallidos por
    `servired_acciones_auditadas_total`, archivado y anclas).
  - **Se quedan:** las operativas (servicios caídos, DLQ, outbox, antigüedad, 5xx, latencia,
    accesos denegados y límite de abuso en el gateway).
  - **Notificación:** Alertmanager enviaba todo al webhook de Auditoría. Ahora tiene un
    receptor que no notifica: las alertas se ven en Alertmanager y Grafana.
- **Configuración:** salen `PERSISTENCIA_CLAVE_AUDITORIA`, `ALERTAS_TOKEN` y
  `AUDITORIA_ANCLAS_GITHUB_*`. Si quedan en un `.env`, se ignoran.

## Alternativas descartadas
- **Dejar el servicio apagado:** el código seguiría ahí sin probarse, y la cola acumularía
  mensajes.
- **Conservar los datos (quitar solo el código):** las tablas quedarían huérfanas, con
  disparadores que nadie entiende.
- **Conservar las alertas de seguridad en otro servicio:** dependían del registro de acciones,
  que es justo lo que se retira. La protección que importa sigue en el gateway: el límite de
  `/auth`, el límite por cuenta y la revocación por jti.

## Consecuencias
- **Menos alcance:** un microservicio menos (quedan seis más RabbitMQ), una cola menos y menos
  secretos. Los eventos que siguen son seis.
- **Sin registro:** no queda un registro de quién hizo qué. Lo más cercano es la historia de
  cada caso (`atencion.historia_caso`) y los logs con `X-Correlation-Id`.
- **En el servidor:** al actualizar, Persistencia aplica V18 y borra los datos de auditoría
  también en Supabase.
