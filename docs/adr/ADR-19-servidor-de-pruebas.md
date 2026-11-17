# ADR-19 · Servidor de pruebas fuera de local, sin costo

**Estado:** aceptada · **Fecha:** 2026-11-17 · **Requisitos:** RNF-SEG1, RNF-SEG5, RNF-DC1

## Contexto
Hace falta probar la plataforma fuera de la máquina de desarrollo, incluidas las apps en
teléfonos con datos móviles. No hay usuarios reales todavía, las apps no se publicarán en
tiendas por ahora y la condición es **no pagar nada**: ni hosting, ni dominio, ni
certificados.

El sistema completo usa unos 2,3 GB de RAM en reposo: dos JVM de unos 400 MB, tres más
de unos 350 MB, tres servicios .NET, RabbitMQ y PostgreSQL.

## Decisión
- **Máquina:** una VM *Always Free* de Oracle Cloud (Ampere A1, ARM, 2 OCPU / 8 GB) con el
  **mismo Docker Compose**, con PostgreSQL y el almacén de fotos dentro. Las imágenes ya se
  construyen para ARM, porque se desarrollan en Apple Silicon.
  - La alternativa sin tarjeta es el propio computador del equipo, encendido mientras se
    prueba.
- **Publicación con Tailscale (plan personal gratuito):**
  - *Funnel* expone a internet **una sola dirección** por HTTPS, con nombre `*.ts.net` y
    certificado automático.
    - En la raíz está el portal público.
    - En `/operacion/` está el portal de operación, que pide inicio de sesión.
    - En `/api` está el gateway, que también usan las apps.
  - *Serve* deja Grafana visible **solo** para los dispositivos de la red Tailscale del
    equipo.
  - Se probó antes un modelo "solo app" (como Waze), con el portal público privado. Se
    descartó a favor de una sola dirección para todos: el ciudadano ve el portal público
    sin cuenta y el personal entra a sus funciones con su sesión. Los reportes y las
    denuncias se hacen solo desde las apps: la web pública no ofrece ninguno de los dos.
- **`infra/servidor/compose.servidor.yaml`** se aplica encima del compose normal:
  - Todo escucha solo en `127.0.0.1`. El gateway queda ahí para diagnóstico y para las
    pruebas e2e.
  - La red `borde` tiene una subred fija (`172.30.0.0/24`), para que nginx confíe en el
    `X-Forwarded-For` del proxy local (`set_real_ip_from 172.30.0.1`) y en nadie más.
    Sin esto, todas las peticiones llegarían desde la IP del proxy y el límite de abuso del
    gateway (ADR-17) sería uno solo para todos.
  - Por defecto `IP_REAL_DESDE=127.0.0.1`, es decir, nadie es de confianza, así que el
    entorno local no cambia.
- **`infra/servidor/servired.sh`** encapsula la operación: preparar (secretos nuevos y clave
  JWT fija), arrancar, publicar, actualizar con `git pull` y construcción en la VM, estado y
  detener. **`instalar-vm.sh`** prepara una VM Ubuntu desde cero.
- **Apps sin tiendas:**
  - En Android, `-PapiBase`.
  - En iOS, `Configuracion/*.xcconfig` más un `Local.xcconfig` no versionado, con la URL,
    el *Personal Team* gratuito y un identificador propio.

## Alternativas descartadas
- **Render, Railway, Fly.io, Koyeb y la e2-micro de Google:** su capa gratuita (256 MB a
  1 GB, con suspensión o un solo servicio) no alcanza para siete servicios más RabbitMQ y
  PostgreSQL.
- **Supabase gratuito:** se pausa tras 7 días sin uso. Para pruebas esporádicas estorba más
  de lo que ayuda; queda como opción documentada en el README raíz.
- **Dominio propio, Caddy y Let's Encrypt:** implica pagar el dominio y abrir los puertos
  80 y 443. Tailscale da HTTPS y nombre estable sin ninguna de las dos cosas.
- **Registro de imágenes y CI de despliegue:** con un solo servidor de pruebas, construir en
  la VM con `git pull` es suficiente y no cuesta nada.

## Consecuencias
- El portal de operación (`/operacion/`) queda en internet protegido por el inicio de sesión, el límite de
  `/auth` (20 por minuto por cliente) y las alertas de fuerza bruta (ADR-16).
- La dirección pública es la de Tailscale, por ejemplo `https://servired.<red>.ts.net`. Si
  cambia el nombre de la red Tailscale, hay que recompilar las apps.
- Funnel tiene límites de ancho de banda pensados para pruebas, no para producción.
- No hay copias de seguridad automáticas: es un entorno de pruebas.
- Pasar a producción (dominio, base gestionada, tiendas) no cambia el código: solo el
  compose de despliegue y la base de las apps.
