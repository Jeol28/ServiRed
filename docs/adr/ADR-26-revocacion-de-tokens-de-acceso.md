# ADR-26: Revocación de tokens de acceso por jti en el gateway

## Estado
Aceptada.

## Contexto
El gateway valida el JWT de acceso con la firma del JWKS de Identidad, sin consultar a
nadie. Es rápido y no depende de Identidad en cada solicitud, pero un token firmado sigue
sirviendo hasta que expira, a los 15 minutos, aunque:
- un administrador haya **desactivado** la cuenta (por ejemplo, un operador despedido);
- la cuenta se haya **suprimido** (derecho de supresión);
- se haya **cambiado la clave** por sospecha de robo;
- se haya **reutilizado un refresco**, señal de robo, que revoca todas las sesiones;
- se haya **cerrado sesión** en ese dispositivo.

Las sesiones ya se revocaban en esos casos, así que el refresco dejaba de renovar. Pero el
token de acceso que el atacante o el ex operador ya tenía seguía sirviendo hasta 15
minutos.

## Decisión

### Cada sesión conoce su token de acceso
Cada fila de `identidad.sesiones` es un token de refresco, y con cada una se emite exactamente
un token de acceso, al iniciar sesión o al rotar. La migración V12 guarda en la sesión el
`jti_acceso`, su `acceso_expira_en` y, al revocarlo, `acceso_revocado_en`.

Identidad **reserva** el jti y la expiración (`EmisorTokens.planear()`) antes de guardar la
sesión, y firma el token con ellos. En la rotación, la cuenta, con su rol y empresa, llega
recién cuando Persistencia rota la sesión.

### Qué revoca un acceso
| Evento | Accesos revocados |
|---|---|
| Desactivar, suprimir, cambiar la clave, refresco reutilizado | Todos los aún vigentes de la cuenta, también los de sesiones ya reemplazadas |
| Cerrar sesión | El de esa sesión y los de las que reemplazó (la cadena de ese dispositivo); no los de otros dispositivos |
| Rotación normal | Ninguno: el cliente cambió legítimamente de token |

### Cómo llega al gateway
- **Persistencia** ofrece `GET /interno/v1/sesiones/accesos-revocados?desde=`, solo para
  Identidad: los revocados que aún no expiran.
- **Identidad** la reexpone en `GET /interno/v1/tokens-revocados`, junto con su hora
  (`generadaEn`). Como el JWKS, no exige autenticación: el gateway no la enruta, el puerto de
  Identidad no se publica, y solo contiene jti aleatorios de tokens que ya no sirven.
- **El gateway** (`SincronizadorRevocacion`):
  - carga la lista completa al arrancar;
  - cada 5 s pide solo lo revocado desde la última `generadaEn`, menos 30 s de
    solapamiento, para no perder una revocación cuya transacción confirmó tarde;
  - guarda `jti → expiración` en memoria y olvida lo expirado.
- **El validador `noRevocado`** se agrega a los de la firma, el emisor y la audiencia, y
  responde **401** como ante cualquier token inválido. La app intenta renovar, y como la
  sesión también está revocada, vuelve al ingreso.

### Si Identidad no responde
El gateway conserva la última lista y sigue atendiendo (*fail-open*). Rechazar todo el
tráfico autenticado por una caída de Identidad sería peor, y los tokens duran 15 minutos.
La métrica `servired_revocacion_antiguedad_segundos` dispara la alerta
`ListaDeRevocacionDesactualizada` si pasa de 60 s.

## Alternativas descartadas
- **Revocar por cuenta («tokens de `sub` emitidos antes de T»):** no distingue
  dispositivos, así que cerrar sesión en el teléfono expulsaría también al portal. Lo
  pedido es por jti.
- **Consultar a Identidad en cada solicitud (introspección):** suma una llamada a cada
  solicitud y hace de Identidad un punto único de falla para toda la API.
- **Acortar los tokens a 1 minuto:** multiplica las renovaciones y aun así deja una
  ventana.
- **Eventos por RabbitMQ:** el gateway no usa el broker, y cada réplica necesitaría su
  cola. La consulta incremental es igual de rápida y sobrevive reinicios: al arrancar carga
  todo.

## Consecuencias
- **Ventana:** de hasta 15 minutos pasa a unos 5 s. La e2e midió entre 2,5 y 4,5 s.
- **Memoria:** la lista solo crece con revocaciones de los últimos 15 minutos.
- **Carga:** cada réplica del gateway hace una consulta liviana cada 5 s. Persistencia la
  resuelve con un índice parcial.
- **Tokens emitidos antes del cambio** no tienen jti guardado y no se pueden revocar, pero
  expiran en 15 minutos.
- **Pruebas:**
  - Persistencia: integración de qué revoca cada evento, el filtro `desde` y el acceso solo
    para Identidad.
  - Identidad: el jti guardado es el del token, y el endpoint.
  - Gateway: `ListaRevocacionTest`, y un token revocado recibe 401.
  - Reglas de Prometheus (promtool).
  - e2e `revocacion-tokens.py`.
