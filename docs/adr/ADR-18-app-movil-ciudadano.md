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
- **Mapas:** MapLibre con el estilo vectorial Positron de OpenFreeMap
  ([ADR-34](ADR-34-mapa-base-openfreemap.md); antes, teselas raster de OpenStreetMap). Se usa la variante
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

## Actualización: la app centrada en el mapa
Se rediseñan las dos apps al estilo de las apps de navegación colaborativa: el mapa es la
pantalla principal y reportar lleva tres toques. Es un diseño visual propio de SERVI-RED.
- **El mapa muestra:**
  - la ubicación del ciudadano;
  - íconos por servicio de los cortes a menos de 1 km (`GET /reportes/cercanos`) y de los
    reportes propios activos;
  - al alejar, las zonas con cortes del país (`GET /mapas/publico`, agregadas y anónimas,
    k ≥ 3).
  Se refresca cada minuto mientras está visible.
- **Reportar:** una hoja con servicio, alcance y «desde cuándo» («ahora» por defecto). La
  ubicación es la del GPS y se ajusta moviendo el mapa bajo un pin fijo. Se reutilizan el
  mismo modelo de vista, las reglas y el envío sin conexión.
- **Tarjetas de un toque:**
  - «Sí, también me afecta» / «Aquí no» para los cortes cercanos (validación, RF-4).
  - «¿Ya volvió la luz?» para los propios activos, que registra el restablecimiento con la
    hora actual (RF-3).
- **Menú lateral** con Mis reportes, Denuncia anónima y Mi cuenta. Se retiran las pestañas,
  la pantalla de cercanos y el formulario de reporte anterior.
- Los íconos se dibujan como imágenes (círculo del color del servicio y su emoji) en una
  capa de símbolos de MapLibre; el estilo raster de OpenStreetMap no tenía tipografías
  (se mantiene con OpenFreeMap, ADR-34).

## Actualización: explorar sin cuenta
La app ya no empieza en el ingreso: abre siempre en el mapa. Sin cuenta:
- se ven las zonas con cortes del país (`GET /mapas/publico`, el mismo dato abierto del
  portal público: agregado y con k ≥ 3);
- **no** se ven los cortes exactos cercanos ni se valida: esos datos son de ciudadanos
  identificados (RNF-DAT2), y `GET /reportes/cercanos` sigue exigiendo sesión;
- la denuncia anónima (RF-5) está en el menú y en la invitación de «Reportar»;
- «Reportar» abre una invitación: «Crear mi cuenta», «Ya tengo cuenta: ingresar» o la
  denuncia anónima. Ingresar también está en la barra superior y en el menú.

Al abrir la sesión, la navegación se descarta y el mapa vuelve con todo. Con cuenta, la
denuncia anónima sigue en el menú: sigue sin enviar ningún dato de la cuenta (RNF-SEG4).
No cambia el servidor ni sus contratos.

## Consecuencias
- Si la clave del Keystore se pierde (por ejemplo, al restaurar el sistema), la app
  pide ingresar de nuevo: el cofre se lee como vacío.
- Un reporte pendiente se borra si el ciudadano cierra sesión antes de que se envíe.
  La pantalla de cuenta lo advierte.
- El APK incluye MapLibre para todas las arquitecturas (~44 MB). Para publicarlo
  conviene un App Bundle (AAB), que divide el paquete por ABI.
