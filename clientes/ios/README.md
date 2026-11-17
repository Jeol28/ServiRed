# App iOS del ciudadano

SwiftUI (Swift 6 con concurrencia estricta), MVVM con `@Observable`, URLSession async/await,
SwiftData, Keychain, CoreLocation, PhotosUI más la cámara del sistema y MapLibre (OpenStreetMap).
Tiene **las mismas pantallas y el mismo flujo que la [app Android](../android/README.md)**.
Usa solo el gateway y el contrato [`servired-api.v1.yaml`](../../contratos/api-publica/servired-api.v1.yaml).
Las decisiones están en [ADR-18](../../docs/adr/ADR-18-app-movil-ciudadano.md).

| Pantalla | Qué hace | Requisito |
|---|---|---|
| Ingreso / registro | Correo y clave. El registro exige autorizar el tratamiento de datos (Ley 1581). Da acceso a la denuncia sin cuenta | RF-11 |
| Mis reportes | Reportes del ciudadano (se ven sin conexión) y los pendientes de envío; deslizar para actualizar | RF-2 |
| Reportar un corte | Servicio, magnitud, desde cuándo, ubicación (GPS o tocando el mapa), descripción y hasta 3 fotos | RF-1, RF-2 |
| Detalle del reporte | Estado, historia, validaciones, mapa, fotos, agregar foto y "el servicio volvió" | RF-3 |
| Cercanos | Cortes de otros a menos de 1 km; confirmar o controvertir el corte y su regreso | RF-4 |
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

La base del gateway está en `API_BASE` (`project.yml`). La configuración Release usa HTTPS.

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
