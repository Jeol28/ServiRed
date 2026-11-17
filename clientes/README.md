# Clientes

Todos consumen **únicamente el gateway** y el mismo contrato
[`contratos/api-publica/servired-api.v1.yaml`](../contratos/api-publica/servired-api.v1.yaml).
Mapas con OpenStreetMap, sin licencias propietarias.

| Cliente | Pila | Fase |
|---|---|---|
| `web/portal-operador` | Angular (standalone, Router, HttpClient con interceptores JWT y X-Correlation-Id, guards por rol), Leaflet | 8 ✅ |
| `web/portal-publico` | Angular, Leaflet | 8 ✅ |
| `web/compartido` | Librería Angular: modelos, servicios HTTP, componentes de mapa | 8 ✅ |
| `android` | Kotlin, Jetpack Compose, MVVM, Retrofit, Room, CameraX, Fused Location, MapLibre | 9 ✅ |
| `ios` | Swift, SwiftUI, MVVM, URLSession async/await, SwiftData, CoreLocation, PhotosUI, MapLibre | 10 ✅ |

Android e iOS tienen las mismas pantallas y el mismo flujo. Cada cliente tiene
su propio README: [web](web/README.md), [android](android/README.md), [ios](ios/README.md).
