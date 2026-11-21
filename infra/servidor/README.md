# Servidor de pruebas fuera de local

Para probar SERVI-RED desde cualquier lugar (incluido el teléfono, con datos móviles)
**sin pagar nada**: el mismo Docker Compose en una máquina que siempre esté encendida,
publicado con [Tailscale](https://tailscale.com). Decisión y alternativas en
[ADR-19](../../docs/adr/ADR-19-servidor-de-pruebas.md).

```
Internet ──HTTPS──▶ Tailscale Funnel ──▶ 127.0.0.1:4300 portal público
                                          ├─ /            portal público (sin cuenta)
                                          ├─ /operacion/  portal de operación (con inicio de sesión)
                                          └─ /api         gateway ─▶ servicios   (lo usan también las apps)
```

- **Nada escucha fuera de 127.0.0.1.** La VM no abre puertos; Tailscale entra por una
  conexión saliente.
- **Una sola dirección pública (Funnel):** `https://<máquina>.<su-red>.ts.net/`, con
  certificado automático.
  - En la raíz está el portal público: mapa, indicadores y datos abiertos. Reportes y
    denuncias se hacen solo desde las apps.
  - En `/operacion/` está el portal de operación, que pide inicio de sesión. El portal
    público tiene el botón «Acceso del personal».
  - En `/api` está el gateway: las apps usan esta misma dirección como base.
- **Privado (Serve):** con observabilidad, Grafana en `:10000`. Solo lo ven sus dispositivos
  con Tailscale.
- **RabbitMQ queda dentro de la máquina.** PostgreSQL y el almacén de fotos también, salvo
  que use Supabase (recomendado, ver más abajo).

## Opción A (recomendada): VM gratuita de Oracle Cloud

1. Cree una cuenta en [Oracle Cloud Free Tier](https://www.oracle.com/cloud/free/). Pide
   una tarjeta **solo para verificar identidad**: los recursos *Always Free* no cobran
   mientras no actualice la cuenta a *Pay As You Go*. La región de origen no se puede
   cambiar después; si no hay capacidad ARM, pruebe otra al registrarse.
2. Cree una instancia **VM.Standard.A1.Flex** (ARM) con Ubuntu 24.04, **2 OCPU y 8 GB**
   y su llave SSH. No abra puertos en la *security list*: no hacen falta.
   - Con 8 GB el uso de memoria queda cerca del 35 %. Oracle recupera las VM gratuitas que
     pasan 7 días con CPU, red **y** memoria por debajo del 20 %.
3. Cree una cuenta gratuita de Tailscale. En la
   [consola](https://login.tailscale.com/admin/dns) active **MagicDNS** y
   **HTTPS Certificates**. Funnel se autoriza la primera vez que se usa.
   - Las apps y los portales **no** necesitan Tailscale en el dispositivo: Funnel los deja
     en internet abierto. Solo Grafana es privado.
4. Entre por SSH y ejecute:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/Jeol28/ServiRed/main/infra/servidor/instalar-vm.sh | bash
   ```

   Abra el enlace de Tailscale que aparece. El script instala Docker y Tailscale, clona el
   repositorio y genera `.env` con secretos nuevos (imprime la clave del administrador).
   Luego **pregunta si usar Supabase**: responda `s` y pegue los mismos datos de su
   proyecto, para que la VM use la misma base y las mismas fotos que ya tiene (ver «Base de
   datos y fotos en Supabase»). Al final levanta todo y muestra las direcciones. Si el repositorio es privado, clónelo antes con
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

## Base de datos y fotos en Supabase (recomendado)

Por defecto, PostgreSQL y el almacén de fotos corren dentro de la máquina: si la VM se
borra, los datos se pierden. Con Supabase (plan gratuito) viven fuera de la VM. Solo
persistencia se conecta a Supabase, así que no se cambia código.

1. En [supabase.com](https://supabase.com) cree un proyecto en la región **South America
   (São Paulo)** y anote la clave de la base de datos.
2. **Storage:** cree un bucket **privado** llamado `evidencias`.
3. **Project Settings > Storage > S3 Connection:** anote el *endpoint* y la región, y cree
   un *access key* (ID y secreto).
4. En la máquina (VM o su computador), desde la raíz del repositorio:

   ```bash
   infra/servidor/servired.sh supabase    # pide la URI del Session pooler, la clave y los datos S3
   infra/servidor/servired.sh arrancar    # persistencia crea las tablas en Supabase al arrancar
   ```

- **Sus datos no pasan por ningún otro lugar.** Las claves se escriben solo en `.env`, entre
  comillas simples para que un `$` no se interprete, con permisos `600`. La configuración
  anterior queda en `.env.antes-supabase`.
- **Debe ser el *Session pooler*** (`…pooler.supabase.com:5432`). La conexión directa solo
  es IPv6 y el *Transaction pooler* no admite las migraciones; el comando rechaza los dos.
- **Con Supabase no se levantan el PostgreSQL ni el almacén locales**
  (`compose.supabase.yaml`). Para volver a lo local:
  `cp .env.antes-supabase .env && servired.sh arrancar`.
- **Las tablas de SERVI-RED viven en esquemas propios** (`identidad`, `reportes`,
  `atencion`…). La API REST pública de Supabase solo expone `public`, así que no quedan
  accesibles por ella. Si no la va a usar, puede desactivarla en *Project Settings > Data API*.
- **El proyecto gratuito se pausa** tras unos 7 días sin uso. Los datos se conservan;
  reactívelo desde el panel antes de volver a probar.
- **Las pruebas e2e** consultan la base que indique `.env`, local o Supabase (con un `psql`
  efímero en Docker).

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
- El portal de operación está en internet, protegido por el inicio de sesión. El gateway
  limita `/auth` a 20 intentos por minuto por cliente y el portal rechaza las cuentas de ciudadano. Cambie la clave del administrador
  generada si la compartió.
- La consola de RabbitMQ y Grafana no están en internet.
- nginx toma la IP real del cliente solo cuando la conexión viene del proxy local
  (`172.30.0.1`). Así el límite de abuso del gateway sigue siendo por cliente.
- Es un entorno de **pruebas**, sin copias de seguridad automáticas. Para guardar los
  datos: `docker compose exec postgres-local pg_dump -U servired servired > respaldo.sql`.
