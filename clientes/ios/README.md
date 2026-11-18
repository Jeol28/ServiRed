# App iOS del ciudadano

SwiftUI (Swift 6 con concurrencia estricta), MVVM con `@Observable`, URLSession async/await,
SwiftData, Keychain, CoreLocation, PhotosUI más la cámara del sistema y MapLibre (OpenStreetMap).
Tiene **las mismas pantallas y el mismo flujo que la [app Android](../android/README.md)**.
Usa solo el gateway y el contrato [`servired-api.v1.yaml`](../../contratos/api-publica/servired-api.v1.yaml).
Las decisiones están en [ADR-18](../../docs/adr/ADR-18-app-movil-ciudadano.md).

| Pantalla | Qué hace | Requisito |
|---|---|---|
| Sin cuenta | La app abre igual en el mapa: zonas con cortes del país (agregadas) y denuncia anónima. «Reportar» invita a crear la cuenta o ingresar. Los cortes exactos cercanos son solo con cuenta (RNF-DAT2) | RF-5, RF-9 |
| Ingreso / registro | Correo y clave, desde «Ingresar» o la invitación de «Reportar». El registro exige autorizar el tratamiento de datos (Ley 1581) | RF-11 |
| **Mapa (pantalla principal)** | Mapa a pantalla completa centrado en el ciudadano. Muestra íconos por servicio (⚡ luz, 💧 agua, 🔥 gas, 🌊 alcantarillado, 🗑️ basura) de los cortes a menos de 1 km y de los propios (con anillo oscuro). Al alejar, zonas con cortes del país (agregadas, k ≥ 3). Se refresca cada minuto | RF-2, RF-4, RF-9 |
| Tarjeta de corte cercano | Al tocar un ícono: «Sin luz · a 188 m · 3 vecinos lo confirmaron», con **Sí, también me afecta** / **Aquí no** | RF-4 |
| «¿Ya volvió la luz?» | Si el ciudadano tiene un corte activo, la pregunta aparece sola: **Sí, ya volvió** (registra el restablecimiento y la duración) o **Todavía no** | RF-3 |
| Reportar (hoja) | Botón grande **Reportar**: servicio, alcance y enviar. La hora es «ahora» y la ubicación la del GPS, salvo que se cambien (se ajusta moviendo el mapa bajo un pin). Foto y descripción opcionales. Sin red queda pendiente | RF-1, RF-2 |
| Menú lateral (☰) | Con cuenta: Mis reportes, Denuncia anónima y Mi cuenta. Sin cuenta: Denuncia anónima e Ingresar o crear cuenta | — |
| Mis reportes / Detalle | Lista (también sin conexión) y detalle con historia, validaciones, fotos y restablecimiento | RF-2, RF-3 |
| Denuncia anónima | Servicio, ubicación, descripción y foto opcional, sin ningún dato de la cuenta ni del teléfono | RF-5 |
| Cuenta | Editar el nombre, cambiar la clave, cerrar sesión y eliminar la cuenta | RF-11 |

## Estructura

```
ServiRed/
  Dominio/      modelos, reglas (las mismas del servidor y de Android) y puertos
  Datos/        Remoto (cliente HTTP, DTO, fechas con zona), Sesion (tokens, Keychain),
                Local (SwiftData), repositorios, Dispositivo (ubicación, fotos, segundo plano)
  UI/           vistas SwiftUI y modelos de vista por funcionalidad; componentes (mapa, fotos)
  App/          punto de entrada y Contenedor (inyección de dependencias manual)
project.yml     definición del proyecto (XcodeGen); ServiRed.xcodeproj se genera de aquí
```

## Ejecutar

Requisitos: Xcode 26 o superior. El compose de la raíz debe estar arriba
(`docker compose up -d --build --wait`). El simulador comparte la red de la Mac, así que
`http://localhost:8080/` llega al gateway; ATS solo permite HTTP hacia la red local.

```bash
open ServiRed.xcodeproj            # y Run sobre un simulador de iPhone
# Si se cambia project.yml:  brew install xcodegen && xcodegen generate
```

La base del gateway (`API_BASE`), el identificador y el equipo de firma están en
`Configuracion/Debug.xcconfig` y `Release.xcconfig`. La configuración Release usa HTTPS.

### En su iPhone, contra el servidor de pruebas

Sin tiendas ni cuenta de pago:

1. Copie `Configuracion/Local.xcconfig.ejemplo` a `Configuracion/Local.xcconfig`. Ese
   archivo no se versiona.
2. Ponga la dirección del servidor, su *Personal Team* (Xcode > Settings > Accounts, con
   su Apple ID gratuito) y un identificador propio.
3. Conecte el iPhone, active el *Modo de desarrollador* y use Run en Xcode.

Firmada con un Apple ID gratuito, la app caduca a los 7 días: se vuelve a instalar igual.
Ver [infra/servidor](../../infra/servidor/README.md).

## Pruebas

```bash
xcodebuild -scheme ServiRed -destination 'platform=iOS Simulator,name=iPhone 17' test
```

- **Unitarias (Swift Testing):** reglas; fechas con zona; gestor de tokens (un solo uso,
  renovaciones simultáneas, petición que no salió); cliente HTTP con un transporte en memoria
  (JWT, correlationId, renovación ante 401, errores problem+json); denuncia sin
  identificadores; outbox con SwiftData en memoria (idempotencia, fotos con id estable,
  fallos definitivos); y modelos de vista.
- **Flujo completo contra el gateway real:** registro, reporte, validación por un segundo
  ciudadano, restablecimiento, denuncia y supresión de ambas cuentas. Se omite si el compose
  no está arriba.
- **Interfaz (XCUITest):** registrarse desde la pantalla, llegar a «Mis reportes» y eliminar
  la cuenta.
- **Capturas de todas las pantallas** (bajo demanda):
  `TEST_RUNNER_SERVIRED_CAPTURAS=/ruta xcodebuild ... test -only-testing:ServiRedUITests/RecorridoCapturasUITests`

## Seguridad y privacidad

- JWT en memoria. El refresco va en el Keychain (`AfterFirstUnlockThisDeviceOnly`: ni iCloud
  ni copias a otro dispositivo), es de un solo uso y nunca se reenvía si pudo llegar al
  servidor.
- URLSession efímera: sin cookies, sin caché en disco y con un agente genérico.
- Las fotos se redibujan antes de guardarse (sin GPS, modelo ni fecha), con protección de
  archivo y fuera de las copias de seguridad.
- Ubicación solo con la app en uso. Al cerrar sesión se borran los reportes y los pendientes
  locales.
