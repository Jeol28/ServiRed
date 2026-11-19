# ADR-30: Token de refresco de los portales en una cookie HttpOnly

## Estado
Aceptada. Reemplaza la decisión de ADR-17 de guardar el refresco en `sessionStorage`.

## Contexto
El portal de operación guardaba el token de refresco (14 días) en `sessionStorage`. La CSP
estricta (`script-src 'self'`, sin scripts en línea) y no usar `innerHTML` mitigaban el
riesgo, pero una XSS que lograra ejecutarse (una dependencia comprometida, un fallo de
saneamiento) podía leer ese refresco y llevarse la sesión **por días**, no solo por los 15
minutos de un token de acceso. ADR-17 dejaba la cookie HttpOnly como mejora, porque «exigiría
cambiar Identidad y el contrato».

## Decisión
**El gateway traduce entre la cookie y el cuerpo.** Identidad no cambia.

- **Activación:** solo si la petición trae la cabecera `X-Sesion-Web: cookie`. El portal la
  envía en `login`, `refrescar` y `cerrar-sesion`. Las apps móviles no la envían y siguen
  como antes: reciben el refresco en el JSON y lo guardan cifrado en el dispositivo.
- **Filtro `FiltroSesionWeb`:**
  - **Ingreso y renovación:** saca `tokenRefresco` de la respuesta de Identidad y lo devuelve
    en la cookie `servired_refresco`.
  - **Renovación y cierre:** toma el refresco de la cookie y se lo pasa a Identidad en el
    cuerpo, como siempre. Ni la cookie ni la cabecera llegan a Identidad.
  - **Fallo o cierre:** si la renovación falla o se cierra la sesión, expira la cookie. Sin
    cookie, renovar responde 401 sin llamar a Identidad.
- **Atributos de la cookie:**

  | Atributo | Por qué |
  |---|---|
  | `HttpOnly` | ningún script la lee, ni siquiera una XSS |
  | `SameSite=Strict` | no viaja en peticiones desde otro sitio |
  | `Path=/api/v1/auth` | no viaja con el resto de la API |
  | `Secure` (salvo en `localhost`) | solo por HTTPS; en local no hay HTTPS |
  | sin `Max-Age` | dura la sesión del navegador, como antes |

- **CSRF:** además de `SameSite=Strict`, la cookie solo se usa con la cabecera
  personalizada. Un sitio ajeno no puede enviarla sin una preflight CORS, y el gateway no la
  autoriza. Las denuncias anónimas siguen sin cookies: nginx las quita en esa ruta.
- **Portal:**
  - **Token de acceso:** sigue solo en memoria.
  - **`sessionStorage`:** solo guarda la marca no secreta `servired.sesion`, para intentar
    restaurar la sesión tras recargar.
  - **Renovación entre pestañas:** se turna con **Web Locks**. Las pestañas ahora comparten
    la cookie, y si dos usaran el mismo refresco a la vez, Identidad lo tomaría como robo y
    cerraría todas las sesiones. Dentro de una pestaña ya había una sola renovación
    compartida.
- **Contrato:** cambia solo en lo opcional:
  - la cabecera `X-Sesion-Web`;
  - el cuerpo `{}` en `refrescar` y `cerrar-sesion` con esa cabecera;
  - `tokenRefresco` deja de ser obligatorio en `Sesion`, porque en modo cookie no viene.

## Alternativas descartadas
- **Que Identidad emita la cookie:** mezcla una preocupación del navegador en un servicio
  que también atiende a las apps, y el gateway tendría que reenviar `Set-Cookie` de todos
  modos.
- **Un BFF con sesión en el servidor:** guardaría los tokens en un servicio nuevo con
  estado. Es más seguro aún, porque el navegador ni siquiera ve el token de acceso, pero es
  mucho más infraestructura para el MVP.
- **Cookie también para el token de acceso:** obligaría a protegerse de CSRF en toda la API.
  El token de acceso en memoria dura 15 minutos y no se persiste.

## Consecuencias
- **Contra una XSS:** ya no puede robar la sesión de días. Lo peor que logra es usar el token
  de acceso en memoria mientras la página está abierta (15 min).
- **Pestañas:** una pestaña nueva comparte la cookie. Si la abre sin la marca, ve la pantalla
  de ingreso, como antes.
- **Navegadores sin Web Locks** (anteriores a 2022): renuevan sin candado. Dos pestañas
  renovando en el mismo instante podrían cerrar la sesión; ingresar de nuevo lo resuelve.
- **Pruebas:**
  - `GatewaySesionWebTest` (6): la cookie y sus atributos, que no es `Secure` en `localhost`,
    las apps sin cambios, la rotación, la cookie ausente o inválida, y el cierre.
  - Las pruebas del portal (28 + 13).
  - e2e `sesion-web.py`, por el nginx del portal: la cookie se rota, una cookie reusada se
    rechaza y revoca la sesión, el cierre funciona, y las apps no cambian.
