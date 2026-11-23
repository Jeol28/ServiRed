# ADR-17 · Portales web: sesión en el navegador, proxy y anonimato

**Estado:** aceptada · **Fecha:** 2026-11-17 · **Requisitos:** RF-5 a RF-13, RNF-SEG1, RNF-SEG4, RNF-SEG5, RNF-DAT2, RNF-USA

## Contexto
La fase 8 agrega dos portales Angular: el de **operación** (operador, regulador y
administrador) y el **público** (mapa, indicadores y datos abiertos). Ambos solo usan el gateway y el contrato `servired-api.v1.yaml`. Había
que decidir dónde guardar los tokens, cómo llegar al gateway sin CORS, cómo
mantener el anonimato de las denuncias en el navegador y cómo conservar el
límite de abuso por cliente detrás de un proxy.

## Decisión
- **Workspace único** (`clientes/web`): dos aplicaciones standalone y la librería
  `compartido` (modelos del contrato, sesión, interceptores, guardias, servicios
  de API, mapa Leaflet, estilos). Angular 22, señales, carga diferida por ruta.
- **Tokens:** el JWT de acceso (15 min) vive **solo en memoria** (una señal); el
  token de refresco, en `sessionStorage`, para sobrevivir a una recarga y morir
  con la pestaña. Nada en `localStorage` ni en cookies. La renovación es única
  y compartida: el refresco se retira del almacenamiento **antes** de usarlo,
  porque Identidad trata su reutilización como robo y revoca la familia.
- **Interceptores:** `X-Correlation-Id` nuevo por petición (se conserva en el
  reintento tras renovar, así la traza es una sola) y JWT con reintento único
  ante 401. Las rutas `/api/v1/auth/**` y `/api/v1/denuncias` nunca llevan JWT.
- **Guardias por rol** solo ordenan la navegación; la autorización real sigue en
  el gateway y en cada servicio. El regulador ve casos y denuncias sin acciones.
- **Mismo origen:** cada portal es una imagen nginx (sin privilegios) que sirve
  la SPA y hace de proxy de `/api/` al gateway. No hay CORS ni otro servicio
  expuesto. CSP estricta (`script-src 'self'`, sin marcos; mapa base solo de
  `tiles.openfreemap.org`, [ADR-34](ADR-34-mapa-base-openfreemap.md)), por eso se desactiva el CSS crítico en línea.
- **Denuncias anónimas (RNF-SEG4): solo en las apps móviles** (ADR-18), igual que los
  reportes. El portal público no tiene formulario de denuncia ni pide la ubicación del
  navegador. En el portal de operación, la moderación se hace por la API autenticada.
  - nginx no registra la ruta `/api/v1/denuncias` (ni acceso ni errores), porque por ella
    entran las denuncias de las apps. Su log JSON nunca incluye la IP.
- **Límite por cliente detrás del proxy (RNF-SEG5):** nginx fija
  `X-Forwarded-For` con la IP del cliente. El gateway solo acepta esa cabecera
  si la conexión viene de un proxy listado en `PROXIES_CONFIABLES`
  (`portal-publico,portal-operador`, resueltos por DNS cada 30 s) y toma la
  última entrada; en otro caso la ignora, así que nadie la falsifica llamando
  al gateway directamente. Además, el gateway ya no reenvía `X-Forwarded-*` ni
  `Forwarded` a los servicios: la IP no sale del gateway.
- **Empresas:** la API v1 no publica el catálogo de empresas; el portal de
  operación muestra los nombres de la semilla y, para un identificador
  desconocido, el UUID.
- **Una sola dirección:** el portal de operación se compila con base `/operacion/` y el
  nginx del portal público lo sirve en esa ruta (con `^~`, para que la regla de caché de
  los archivos con hash no se adelante). Un ciudadano ve el portal público sin cuenta y el
  personal entra por «Acceso del personal». En local, el portal público está en `:4300`
  (y `/operacion/` dentro de él); `127.0.0.1:4200` queda para diagnóstico.

## Actualización: el mismo estilo de las apps
Los portales adoptan la apariencia de las apps móviles (ADR-18):
- **Barra flotante:** botón ☰, píldora con la marca y la sección, y una acción a la
  derecha («Acceso del personal» en el público, el rol en el de operación). Las secciones
  pasan a un **menú lateral** compartido (`sr-menu-lateral`). Cerrado queda `inert`, se
  cierra con Escape y, al abrirlo, el foco pasa a la primera sección.
- **Portal público:** abre en el mapa a pantalla completa, con:
  - chips de servicio con el emoji de las apps (⚡ 💧 🔥 🌊 🗑️);
  - una tarjeta inferior con el resumen del periodo (24 h, 7 o 30 días);
  - una tarjeta al tocar una zona.
  Las zonas siguen siendo celdas agregadas con k ≥ 3: no cambia lo que se publica.
  Indicadores y Datos abiertos siguen como páginas, desde el menú.
- **Portal de operación:** las bandejas y tablas no cambian, solo la barra y el menú. El
  **mapa operativo** pasa a pantalla completa:
  - íconos por servicio, más grandes según la prioridad;
  - chips de servicio, periodo y situación;
  - una tarjeta con el estado, la prioridad y la empresa del punto tocado.
- Los íconos del mapa se crean con `textContent` (nunca HTML) y se pueden enfocar con el
  teclado. Ambos mapas se refrescan cada minuto.

## Consecuencias
- Recargar la pestaña conserva la sesión; abrir otra pestaña pide ingresar de
  nuevo (aceptable para personal y más seguro).
- Una XSS podría leer el refresco de `sessionStorage`; la CSP estricta y no usar
  `innerHTML` (el mapa inserta texto con `textContent`) reducen ese riesgo. Una
  cookie `HttpOnly` exigiría cambiar Identidad y el contrato; queda como mejora.
  **Resuelto en [ADR-30](ADR-30-refresco-en-cookie-httponly.md):** el refresco va en una cookie
  HttpOnly que maneja el gateway, sin cambiar Identidad.
- Con varios gateways o proxies, `PROXIES_CONFIABLES` debe listarlos todos.
- Al compartir origen, los dos portales comparten `sessionStorage` en una misma pestaña. El
  portal público no guarda nada ahí, y la CSP sin scripts en línea limita el riesgo de que
  un fallo en uno afecte la sesión del otro.
