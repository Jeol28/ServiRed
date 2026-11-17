# compartido

Librería de los portales SERVI-RED (se importa como `compartido`; la ruta apunta a
`src/public-api.ts`, no hace falta compilarla aparte).

| Carpeta | Contenido |
|---|---|
| `lib/modelos.ts` | Tipos del contrato `servired-api.v1.yaml` |
| `lib/sesion/` | `ServicioSesion` (JWT en memoria, refresco de un solo uso) y guardias por rol |
| `lib/http/` | Interceptores de `X-Correlation-Id` y JWT; mensajes de error problem+json |
| `lib/api/` | Servicios por recurso: casos, moderación, cuentas, auditoría, indicadores, denuncias (sin interceptores) |
| `lib/mapa/` | Componente `<sr-mapa>` (Leaflet + OpenStreetMap) |
| `lib/ui/` | Etiquetas, colores, pipes de fecha y duración, filtro de periodo |
| `estilos/_base.scss` | Tokens, modo oscuro, formularios, tablas |

Pruebas: `npx ng test compartido --watch=false`.
