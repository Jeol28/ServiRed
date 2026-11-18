# Contratos

Única fuente de verdad para integrar servicios y clientes. Ningún servicio comparte
código con otro: se integran solo a través de estos contratos (RNF-DAT3).

| Carpeta | Contenido |
|---|---|
| [`api-publica/servired-api.v1.yaml`](api-publica/servired-api.v1.yaml) | OpenAPI 3.1 del **gateway**. Es lo único que consumen los portales y las apps Android/iOS. |
| [`internos/persistencia.v1.yaml`](internos/persistencia.v1.yaml) | OpenAPI 3.1 de la API interna de persistencia (operaciones de negocio). |
| [`eventos/asyncapi.yaml`](eventos/asyncapi.yaml) | AsyncAPI 3.0: canales, productores y consumidores en RabbitMQ. |
| [`eventos/esquemas/`](eventos/esquemas/) | JSON Schema 2020-12: sobre común y `datos` de cada evento versionado. |
| [`eventos/ejemplos/`](eventos/ejemplos/) | Un sobre completo y uno mínimo (sin opcionales) de cada evento, válidos contra su esquema. |

## Reglas de versionado
- HTTP: versión en la ruta (`/api/v1`, `/interno/v1`). Un cambio incompatible crea `v2`
  y la versión anterior se mantiene durante el período acordado.
- Eventos: routing key `<agregado>.<evento>.v<N>`; un cambio incompatible publica
  `v2` en paralelo hasta que todos los consumidores migren.
- Agregar campos opcionales es compatible; quitar o renombrar no lo es.

## Eventos mínimos
| Evento | Productor | Consumidores |
|---|---|---|
| `reporte.creado` | reportes | atencion, indicadores, auditoria |
| `restablecimiento.registrado` | reportes | atencion, indicadores, auditoria |
| `validacion.registrada` | reportes | atencion, indicadores, auditoria |
| `denuncia.recibida` | reportes | atencion, auditoria |
| `caso.actualizado` | atencion | reportes, indicadores, auditoria |
| `caso.cerrado` | atencion | reportes, indicadores, auditoria |
| `accion.auditada` | identidad, atencion, indicadores | auditoria |

## Cómo se verifica que Java y .NET hablan el mismo idioma
Los esquemas son la regla. La comprueban tres niveles de pruebas, y todos validan los
formatos (`uuid`, `date-time`), no solo los tipos:

1. **El contrato es consistente:** `sh pruebas/contratos/verificar-contratos.sh`.
   - El AsyncAPI apunta a esquemas que existen y cada canal usa la routing key `tipo.vN`.
   - Ningún esquema queda huérfano y cada ejemplo cumple su esquema.
   - Hay casos que rompen el contrato y deben rechazarse.

   Corre en un contenedor con `jsonschema`, sin instalar nada en la máquina.
2. **Cada productor y cada consumidor**, en las pruebas de su servicio (`mvnw verify`,
   `dotnet test`), con el mismo contrato:
   - Validador: `com.networknt:json-schema-validator` en Java y `JsonSchema.Net` en .NET.
   - **Productores:** generan sus eventos con el código real, los serializan como viajan y
     los validan. En Reportes, `ContratoEventosTest`; en Identidad,
     `ContratoAccionAuditadaTest`; en Atención e Indicadores, `ContratoEventosPruebas`.
   - **Consumidores:** procesan los ejemplos completo y mínimo con su código real. En
     Reportes, `ConsumidorEventosCasos`; en Atención, `Datos*`; en Indicadores,
     `ProyeccionEventos`; en Auditoría, `Clasificador`.
3. **De punta a punta:** `python3 pruebas/e2e/contrato-eventos.py`.
   - Escucha todo `servired.eventos` mientras recorre flujos que producen los 7 tipos
     desde sus servicios reales.
   - Valida cada mensaje capturado contra su esquema y sus propiedades AMQP.

Un cambio en un esquema que rompa a un productor o a un consumidor falla en su propio
servicio. Agregue un campo opcional al ejemplo `completo` para que los consumidores lo
prueben; el ejemplo `minimo` comprueba que no dependan de los opcionales.

Los contratos crecen fase a fase; cada versión indica la fase en `info.version`.
