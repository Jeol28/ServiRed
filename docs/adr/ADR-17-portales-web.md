# ADR-17 · Portales web: sesión en el navegador, proxy y anonimato

**Estado:** aceptada · **Fecha:** 2026-11-17 · **Requisitos:** RF-5 a RF-13, RNF-SEG1, RNF-SEG4, RNF-SEG5, RNF-DAT2, RNF-USA

## Contexto
La fase 8 agrega dos portales Angular: el de **operación** (operador, regulador y
administrador) y el **público** (mapa, indicadores, datos abiertos y denuncia
anónima). Ambos solo usan el gateway y el contrato `servired-api.v1.yaml`. Había
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
  expuesto. CSP estricta (`script-src 'self'`, sin marcos; imágenes solo de
  `tile.openstreetmap.org`), por eso se desactiva el CSS crítico en línea.
- **Denuncias anónimas (RNF-SEG4):** el portal usa un `HttpClient` **sin
  interceptores** (ni JWT ni correlationId, aunque haya sesión), envía la foto
  con el nombre neutro `foto` y no muestra el identificador interno. nginx no
  registra esa ruta (ni acceso ni errores) y su log JSON nunca incluye la IP.
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

## Consecuencias
- Recargar la pestaña conserva la sesión; abrir otra pestaña pide ingresar de
  nuevo (aceptable para personal y más seguro).
- Una XSS podría leer el refresco de `sessionStorage`; la CSP estricta y no usar
  `innerHTML` (el mapa inserta texto con `textContent`) reducen ese riesgo. Una
  cookie `HttpOnly` exigiría cambiar Identidad y el contrato; queda como mejora.
- Con varios gateways o proxies, `PROXIES_CONFIABLES` debe listarlos todos.
- Al compartir origen, los dos portales comparten `sessionStorage` en una misma pestaña. El
  portal público no guarda nada ahí, y la CSP sin scripts en línea limita el riesgo de que
  un fallo en uno afecte la sesión del otro.
