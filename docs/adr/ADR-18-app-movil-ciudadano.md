# ADR-18 · App móvil del ciudadano: sin conexión, sesión segura y mapas

**Estado:** aceptada · **Fecha:** 2026-11-17 · **Requisitos:** RF-1 a RF-5, RF-11, RNF-DC2, RNF-SEG1, RNF-SEG4, RNF-USA

## Contexto
Las fases 9 y 10 construyen la app del ciudadano en Android y en iOS, con las mismas
pantallas y el mismo flujo. Los cortes suelen coincidir con mala señal, así que
un reporte no puede perderse por falta de red. La app guarda tokens de larga vida y
fotos que pueden llevar la ubicación de la casa del ciudadano.

## Decisión
- **Arquitectura:** MVVM con puertos. Los ViewModel solo conocen interfaces del
  dominio (`RepositorioReportes`, `RepositorioSesion`…). Retrofit, Room, Play
  Services y CameraX quedan en la capa de datos. El dominio no depende de Android y
  se prueba en la JVM. La inyección de dependencias es manual (un `Contenedor`).
- **Sin conexión (outbox en el teléfono):**
  - Un reporte nuevo se guarda primero en Room con un `X-Correlation-Id` fijo y
    luego se envía.
  - Si no hay red, WorkManager lo envía cuando vuelve, con espera exponencial.
  - El servidor usa ese id como clave de idempotencia, así que reintentar nunca
    duplica el reporte.
  - Cada foto tiene un id derivado y estable: `UUID.nameUUIDFromBytes(id/foto/i)`.
  - Un rechazo por datos (400/409) queda como fallido y el ciudadano lo descarta.
  - Los errores de red, 5xx y 429 se reintentan.
  - "Mis reportes" se lee de Room y se actualiza desde el servidor.
- **Sesión:**
  - El JWT de acceso vive solo en memoria.
  - El token de refresco y el resumen de la cuenta se cifran con AES-256-GCM, con la
    clave en el Android Keystore (no exportable). Quedan fuera de los respaldos.
  - El refresco se retira del cofre **antes** de usarlo. Solo vuelve si la petición
    no salió del teléfono (`UnknownHost`, `Connect`). Si se envió y falló, la sesión
    termina: es mejor pedir ingreso que arriesgar una "reutilización" que revoque
    todas las sesiones.
  - Las renovaciones simultáneas se serializan. Un `Authenticator` de OkHttp
    reintenta una vez ante 401, con el mismo correlationId.
  - Al cerrar sesión, cambiar la clave o eliminar la cuenta se borran Room y las
    fotos locales.
- **Fotos:**
  - Se enderezan según el EXIF, se reducen a 2560 px y se recodifican en JPEG.
    Recodificar descarta GPS, modelo y fecha, que nunca salen del teléfono.
  - La galería usa el selector del sistema, que no pide permiso de almacenamiento.
- **Denuncia anónima:**
  - Usa el cliente HTTP público, sin interceptores: ni JWT ni correlationId, aunque
    haya sesión abierta.
  - La foto viaja con el nombre `foto.jpg` y se borra al enviarla.
  - No se guarda nada de la denuncia en el teléfono, y no pide permiso de ubicación
    al abrir.
- **Ubicación:**
  - Fused Location puntual, sin rastreo en segundo plano.
  - Al validar un reporte cercano se envía la ubicación solo para comprobar la
    distancia; el servidor no la guarda.
- **Mapas:** MapLibre con teselas raster de OpenStreetMap. Se usa la variante
  **OpenGL ES** (`android-sdk-opengl`), porque la de Vulkan cierra la app en
  dispositivos y emuladores sin Vulkan.
- **Red:**
  - Solo HTTPS, salvo `localhost` en desarrollo.
  - Android 17 bloquea por defecto el acceso a la red local, que incluye el
    `10.0.2.2` del emulador. Por eso en desarrollo se usa
    `adb reverse tcp:8080 tcp:8080` y `http://localhost:8080/`.
  - La base del gateway se cambia con `-PapiBase=...`.

## Equivalencias en iOS (fase 10)
Las decisiones anteriores se aplican igual en iOS. Cambia la tecnología:

| Android | iOS |
|---|---|
| ViewModel + StateFlow | `@Observable` en el actor principal (Swift 6, concurrencia estricta) |
| Retrofit/OkHttp con interceptores y `Authenticator` | `ClienteAPI` sobre un `Transporte` inyectable (URLSession efímera) |
| `GestorTokens` con `synchronized` | `actor GestorTokens`, con una sola `Task` de renovación compartida |
| Android Keystore + AES-GCM | Keychain `AfterFirstUnlockThisDeviceOnly` |
| Room | SwiftData en un `@ModelActor` |
| WorkManager | `NWPathMonitor` (envía al volver la red) + `BGAppRefreshTask` |
| CameraX + selector del sistema | Cámara del sistema + `PhotosPicker` |
| Fused Location | CoreLocation (`requestLocation`, solo en uso) |
| MapLibre `android-sdk-opengl` | MapLibre iOS 7 (Metal) |

- El simulador de iOS comparte la red de la Mac: `http://localhost:8080/` funciona sin
  reenvíos. ATS solo admite HTTP hacia la red local (`NSAllowsLocalNetworking`).
- En las dos plataformas, el cofre separa "retirar el refresco" de "borrar todo". Así, si
  una renovación no llega a salir del teléfono, la app conserva el resumen de la cuenta y
  abre la sesión sin red.

## Consecuencias
- Si la clave del Keystore se pierde (por ejemplo, al restaurar el sistema), la app
  pide ingresar de nuevo: el cofre se lee como vacío.
- Un reporte pendiente se borra si el ciudadano cierra sesión antes de que se envíe.
  La pantalla de cuenta lo advierte.
- El APK incluye MapLibre para todas las arquitecturas (~44 MB). Para publicarlo
  conviene un App Bundle (AAB), que divide el paquete por ABI.
