# App Android del ciudadano

Kotlin 2.4, Jetpack Compose (Material 3), MVVM, Retrofit + kotlinx.serialization, Room,
WorkManager, CameraX, Fused Location y MapLibre (OpenStreetMap). Usa solo el gateway y
el contrato [`servired-api.v1.yaml`](../../contratos/api-publica/servired-api.v1.yaml).
Las decisiones están en [ADR-18](../../docs/adr/ADR-18-app-movil-ciudadano.md).

## Pantallas (iOS replica las mismas)

| Pantalla | Qué hace | Requisito |
|---|---|---|
| Ingreso / registro | Correo y clave. El registro exige autorizar el tratamiento de datos (Ley 1581). Da acceso a la denuncia sin cuenta | RF-11 |
| Mis reportes | Reportes del ciudadano (se ven sin conexión) y los pendientes de envío; deslizar para actualizar | RF-2 |
| Reportar un corte | Servicio, magnitud, desde cuándo, ubicación (GPS o tocando el mapa), descripción y hasta 3 fotos (cámara o galería). Sin red queda pendiente | RF-1, RF-2 |
| Detalle del reporte | Estado, historia, validaciones, mapa, fotos, agregar foto y "el servicio volvió" (calcula la duración) | RF-3 |
| Cercanos | Cortes de otros a menos de 1 km, en mapa y lista; confirmar o controvertir el corte y su regreso | RF-4 |
| Denuncia anónima | Servicio, ubicación, descripción y foto opcional. No envía ningún dato de la cuenta ni del teléfono | RF-5 |
| Cuenta | Editar el nombre, cambiar la clave, cerrar sesión y eliminar la cuenta (derecho de supresión) | RF-11 |

## Estructura

```
app/src/main/java/co/edu/javeriana/servired/app/
  dominio/      modelos, reglas (las mismas del servidor) y puertos; sin Android
  datos/        remoto (Retrofit, interceptores, errores), sesion (Keystore), local (Room),
                fotos (sin EXIF), ubicacion (Fused), sincronizacion (WorkManager)
  ui/           pantallas Compose y ViewModel por funcionalidad; componentes (mapa, cámara)
  Contenedor.kt inyección de dependencias manual
```

## Ejecutar

Requisitos: Android Studio (o su JDK 21 en `JAVA_HOME`) y el SDK con la plataforma 37.
El compose de la raíz debe estar arriba (`docker compose up -d --build --wait`).

```bash
adb reverse tcp:8080 tcp:8080          # el emulador ve el gateway en localhost:8080
./gradlew installDebug                 # o Run desde Android Studio
```

Android 17 bloquea por defecto el acceso a la red local (incluido `10.0.2.2`), por eso
se usa `adb reverse`. Para otro gateway: `./gradlew installDebug -PapiBase=https://api.ejemplo.co/`.
La versión release solo admite HTTPS.

### En su teléfono, contra el servidor de pruebas

Sin tiendas: conecte el teléfono por USB con la depuración USB activa y ejecute

```bash
./gradlew installDebug -PapiBase=https://servired.<su-red>.ts.net/
```

Para no repetir la dirección, póngala en `~/.gradle/gradle.properties` (fuera del
repositorio): `apiBase=https://servired.<su-red>.ts.net/`. También puede generar el APK
(`./gradlew assembleDebug`, queda en `app/build/outputs/apk/debug/`) y abrirlo en el
teléfono. Ver [infra/servidor](../../infra/servidor/README.md).

## Pruebas

```bash
./gradlew testDebugUnitTest            # JVM: reglas, tokens, interceptores (MockWebServer),
                                       # outbox e idempotencia, denuncia anónima, ViewModel
./gradlew connectedDebugAndroidTest    # emulador: Room, flujo completo contra el gateway real
                                       # y registro desde la interfaz (requiere adb reverse)
./gradlew lintDebug assembleRelease    # lint y compilación con R8
```

El flujo completo (`FlujoCiudadanoE2E`) hace lo siguiente contra el compose:

1. Registra a un ciudadano y crea un reporte.
2. Comprueba el municipio resuelto (DIVIPOLA).
3. Un segundo ciudadano lo ve como cercano y lo confirma; repetir el voto no cuenta
   dos veces y el autor no puede votar el suyo.
4. Registra el restablecimiento y comprueba la duración.
5. Envía una denuncia anónima.
6. Elimina ambas cuentas.

## Seguridad y privacidad

- JWT en memoria. El refresco va cifrado (AES-GCM con la clave en el Android Keystore),
  es de un solo uso y nunca se reenvía si pudo llegar al servidor.
- Las fotos se recodifican antes de guardarse: no salen GPS, modelo ni fecha.
- Sin respaldo en la nube (`allowBackup=false` y reglas de extracción).
- Ubicación solo mientras la app está en uso; nunca en segundo plano.
- Al cerrar sesión no quedan reportes ni pendientes en el teléfono.
