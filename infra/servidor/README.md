# Servidor de pruebas fuera de local

Para probar SERVI-RED desde cualquier lugar (incluido el teléfono, con datos móviles)
**sin pagar nada**: el mismo Docker Compose en una máquina que siempre esté encendida,
publicado con [Tailscale](https://tailscale.com). Decisión y alternativas en
[ADR-19](../../docs/adr/ADR-19-servidor-de-pruebas.md).

```
Internet ──HTTPS──▶ Tailscale Funnel ──▶ 127.0.0.1:4300 portal público ──/api──▶ gateway ─▶ servicios
Sus dispositivos ──Tailscale──▶ Serve ──▶ 127.0.0.1:4200 portal de operación (y Grafana)
```

- **Nada escucha fuera de 127.0.0.1.** La VM no abre puertos; Tailscale entra por una
  conexión saliente.
- **Público (Funnel):** portal público y API en `https://<máquina>.<su-red>.ts.net/`, con
  certificado automático. Las apps usan esa misma dirección como base.
- **Privado (Serve):** el portal de operación en `https://<máquina>.<su-red>.ts.net:8443/`
  y, con observabilidad, Grafana en `:10000`. Solo los ven sus dispositivos con Tailscale.
- **Todo queda dentro de la máquina**, incluidos PostgreSQL, el almacén de fotos y RabbitMQ.

## Opción A (recomendada): VM gratuita de Oracle Cloud

1. Cree una cuenta en [Oracle Cloud Free Tier](https://www.oracle.com/cloud/free/). Pide
   una tarjeta **solo para verificar identidad**: los recursos *Always Free* no cobran
   mientras no actualice la cuenta a *Pay As You Go*. La región de origen no se puede
   cambiar después; si no hay capacidad ARM, pruebe otra al registrarse.
2. Cree una instancia **VM.Standard.A1.Flex** (ARM) con Ubuntu 24.04, **2 OCPU y 8 GB**
   y su llave SSH. No abra puertos en la *security list*: no hacen falta.
   - Con 8 GB el uso de memoria queda cerca del 35 %. Oracle recupera las VM gratuitas que
     pasan 7 días con CPU, red **y** memoria por debajo del 20 %.
3. Cree una cuenta gratuita de Tailscale e instálelo en su computador y su teléfono.
   En la [consola](https://login.tailscale.com/admin/dns) active **MagicDNS** y
   **HTTPS Certificates**. Funnel se autoriza la primera vez que se usa.
4. Entre por SSH y ejecute:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/Jeol28/ServiRed/main/infra/servidor/instalar-vm.sh | bash
   ```

   Abra el enlace de Tailscale que aparece. El script instala Docker y Tailscale, clona el
   repositorio, genera `.env` con secretos nuevos (imprime la clave del administrador),
   levanta todo y muestra las direcciones. Si el repositorio es privado, clónelo antes con
   un token (`git clone https://<token>@github.com/...`) y ejecute el script desde ahí.
5. Cambie el nombre de la máquina en Tailscale a `servired` para tener una dirección
   corta.

## Opción B: su propio computador

Sin cuenta en la nube y sin tarjeta, pero solo funciona mientras el computador esté
encendido. Instale Tailscale (en macOS, la app; en Linux, el script oficial) e inicie
sesión. Luego, desde la raíz del repositorio:

```bash
infra/servidor/servired.sh preparar     # solo si aún no tiene .env
infra/servidor/servired.sh arrancar
infra/servidor/servired.sh publicar
```

En macOS, si `tailscale` no está en el PATH:
`alias tailscale=/Applications/Tailscale.app/Contents/MacOS/Tailscale`.

## Operación

| Comando | Qué hace |
|---|---|
| `servired.sh estado` | Contenedores, publicación y salud del gateway |
| `servired.sh actualizar` | `git pull` y vuelve a construir y levantar |
| `servired.sh detener` | Apaga todo; los datos se conservan |
| `OBSERVABILIDAD=1 servired.sh arrancar` | Agrega Prometheus, Alertmanager, Grafana y Jaeger |

Las pruebas e2e se pueden correr en el propio servidor (el gateway escucha en
`127.0.0.1:8080`): `COMPOSE_FILE=compose.yaml:infra/servidor/compose.servidor.yaml python3 pruebas/e2e/flujo-reporte.py`.

## Apps en sus dispositivos (sin tiendas)

- **Android:** `./gradlew installDebug -PapiBase=https://servired.<su-red>.ts.net/` con el
  teléfono conectado por USB (depuración USB activa). También puede generar el APK con
  `assembleDebug` y enviárselo. Ver [clientes/android](../../clientes/android/README.md).
- **iOS:** copie `clientes/ios/Configuracion/Local.xcconfig.ejemplo` a `Local.xcconfig`,
  ponga la dirección y su *Personal Team* (Apple ID gratuito) y use Run en Xcode con el
  iPhone conectado. La app firmada así caduca a los 7 días: se reinstala igual. Ver
  [clientes/ios](../../clientes/ios/README.md).

## Seguridad

- `.env` tiene secretos nuevos y solo lo lee su usuario (`umask 077`). Cambie la clave del
  administrador desde el portal si la compartió.
- El portal de operación, la consola de RabbitMQ y Grafana no están en internet.
- nginx toma la IP real del cliente solo cuando la conexión viene del proxy local
  (`172.30.0.1`). Así el límite de abuso del gateway sigue siendo por cliente.
- Es un entorno de **pruebas**, sin copias de seguridad automáticas. Para guardar los
  datos: `docker compose exec postgres-local pg_dump -U servired servired > respaldo.sql`.
