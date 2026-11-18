# ADR-23: La idempotencia pertenece a quien hace la solicitud

## Estado
Aceptada.

## Contexto
Cuando la app reintenta una operación, manda el mismo `X-Correlation-Id`. Persistencia
guarda la pareja (correlación, operación) en `infraestructura.solicitudes_procesadas` y,
si la ve repetida, devuelve el resultado original en vez de repetir la escritura (ADR-06).

La clave no decía quién hizo la solicitud ni sobre qué recurso. Al revisarlo aparecieron
dos defectos:

1. **Otra cuenta con la misma correlación** se trataba como un reintento. En
   `guardarReporte` la respuesta era **el reporte original**, de otra persona, con su
   ubicación y descripción. En el registro de cuentas era la cuenta ajena, con su correo.
2. **La misma correlación sobre otro recurso**, en restablecimiento, validación, fotos y
   actualización de casos, devolvía el recurso que pedía la solicitud nueva, sin aplicar la
   operación. Se descartaba en silencio y se respondía como si hubiera funcionado.

La severidad es baja, porque hay que conocer un UUID aleatorio que solo viaja entre el
teléfono y el servidor. Aun así es un defecto de diseño.

## Decisión
Al reclamar la clave se guarda también una **huella** del titular: `SHA-256` de quién pide
y sobre qué. Si la repetición llega con otra huella o desde otro servicio, no es un
reintento: Persistencia responde **409 `CORRELACION_AJENA`**, sin devolver el resultado
ajeno y sin escribir nada.

| Operación | Titular |
|---|---|
| guardarReporte | la cuenta (el `idReporte` lo genera Reportes en cada intento) |
| registrarValidacion | la cuenta, el reporte y el objeto |
| registrarRestablecimiento, guardarEvidencia | el reporte (Reportes ya comprobó que es del autor; el `idEvidencia` cambia en cada intento) |
| aplicarEstadoDeCaso, abrirCaso | el reporte (la clave es el evento) |
| actualizarCaso | el caso y el autor |
| registrarCuenta | el correo (aún no hay cuenta; se guarda solo la huella) |
| guardarDenuncia | ninguno: es anónima y su correlación la genera siempre el gateway |

- **Datos personales:** se guarda la huella, no el titular, para no copiar a esta tabla ni
  ids de cuenta ni correos.
- **Filas anteriores a la migración V11:** no tienen huella y no se comparan.
- **Los servicios clientes** reconocen el motivo **antes** que cualquier otro 409 de la
  operación y lo devuelven a la app como 409 `CORRELACION_AJENA`. Antes, Reportes lo habría
  leído como «ya restablecido», «límite de fotos» o «conflicto de versión» (y en este caso
  reintentaba con otra correlación), e Identidad como «la empresa no existe».

## Alternativas descartadas
- **Incluir la cuenta en la clave**, que quedaría (cuenta, correlación, operación): dos
  solicitudes distintas compartirían un `correlationId`. Ese id se usa para rastrear una
  transacción en los logs, el outbox y los eventos (RNF-OBS1) y dejaría de identificarla.
  Además, no cubre el segundo defecto (otro recurso con la misma cuenta).

## Consecuencias
- **Para la app:** un 409 `CORRELACION_AJENA` significa «genere un `X-Correlation-Id`
  nuevo». Las apps ya generan uno por operación y lo reusan solo en sus propios
  reintentos, así que en uso normal no aparece.
- **Pruebas:** `OperacionesIntegracionTest` simulaba el reintento con otra cuenta; pasaba
  justamente por el defecto. Ahora reintenta la misma cuenta, y una prueba nueva cubre la
  cuenta ajena.
