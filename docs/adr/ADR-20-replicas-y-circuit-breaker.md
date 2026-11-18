# ADR-20: Réplicas y circuit breaker hacia Persistencia

## Estado
Aceptada.

## Contexto
Persistencia es el único servicio que habla con la base de datos (ADR-06). Todos los
demás dependen de ella, y cada servicio corría con una sola instancia. Ya había timeouts
y reintentos, pero dos problemas quedaban abiertos:

- **Sin circuit breaker:** con Persistencia caída o saturada, cada solicitud esperaba
  sus timeouts y reintentos (hasta ~15 s en Reportes). Así se acumulaban hilos y
  conexiones en los clientes y se le sumaba carga a un servicio que ya no respondía.
- **Punto único de falla:** con una sola instancia, cualquier caída de Persistencia o de
  Reportes tumbaba la funcionalidad completa.

El relay del outbox ya admite varias réplicas (`FOR UPDATE SKIP LOCKED`) y la cadena de
auditoría se serializa con un bloqueo consultivo de PostgreSQL. Persistencia no guarda
estado en memoria.

## Decisión

### Circuit breaker en cada cliente de Persistencia
Cada servicio que llama a Persistencia tiene **un circuito** hacia ella:

| Servicio | Librería | Ventana |
|---|---|---|
| Identidad, Reportes (Java) | Resilience4j 2.2.0 | ≥ 50 % de fallas en las últimas 20 llamadas (mínimo 10) |
| Atención, Indicadores, Auditoría (.NET) | Microsoft.Extensions.Http.Resilience 10.0.0 (Polly) | ≥ 50 % de fallas en 30 s (mínimo 10) |

- **Qué cuenta como falla:** red caída, timeout y 5xx. Un 4xx es un error de la
  petición, no de Persistencia, y no cuenta.
- **Abierto:** cada llamada falla al instante durante 15 s, sin reintentos ni esperas
  de timeout. Después pasa a semiabierto: deja pasar llamadas de prueba (3 en
  Resilience4j, 1 en Polly) y se cierra si responden.
- **Cómo se ve la falla:**
  - En Java es `PersistenciaNoDisponible`: la API responde 503 y el consumidor de
    eventos la trata como falla transitoria (cola de reintento).
  - En .NET, el circuito abierto y el timeout se convierten en `HttpRequestException`
    (503), la misma falla transitoria de siempre. La API responde 503 y el consumidor
    reprograma el mensaje en vez de enviarlo a la cola de fallidos.
- **Reintentos en Java:** cada intento pasa por el circuito. Con el circuito abierto no
  se reintenta.
- **Timeout en .NET:** va dentro del pipeline de Polly. El de `HttpClient` queda 5 s más
  largo, porque una cancelación del `HttpClient` no contaría como falla.
- **Observabilidad:**
  - Java publica `resilience4j_circuitbreaker_state`; .NET publica
    `servired_circuito_persistencia_estado` (0 cerrado, 0,5 semiabierto, 1 abierto).
  - La alerta `CircuitoPersistenciaAbierto` avisa si el circuito sigue abierto 1 min.
  - Los cambios de estado quedan en el log.

### Réplicas con `docker compose up --scale`
Persistencia y Reportes pueden correr con varias réplicas:

```bash
docker compose up -d --build --wait --scale persistencia=2 --scale reportes=2
```

- **Descubrimiento:** por el DNS de Docker. El nombre del servicio resuelve a todas las
  réplicas y una réplica que se cae sale del DNS. No hay balanceador aparte.
- **JVM:** guarda el DNS en caché solo 5 s (`-Dsun.net.inetaddr.ttl=5`; el valor por
  defecto es 30 s), así una réplica caída sale pronto de la rotación.
- **.NET:** renueva sus conexiones cada minuto (`PooledConnectionLifetime`).
- **Gateway:** repite un GET si falla la conexión con una réplica, y la siguiente
  conexión va a otra. Solo ante errores de conexión: un 5xx no se repite, porque sumaría
  carga a una caída.
- **Timeouts del gateway:** 2 s para conectar y 10 s para la respuesta. Antes no tenía
  timeout de respuesta. Cuando una réplica muere de golpe, su IP desaparece y nadie
  cierra la conexión TCP, así que la solicitud en curso quedaba colgada para siempre;
  la prueba con réplicas lo detectó. Ahora esa solicitud termina en timeout y, si es un
  GET, se repite en la otra réplica.
- **Bucket:** al arrancar a la vez, dos réplicas de Persistencia pueden intentar crear el
  bucket. «Ya existe» no es un error.
- **Prometheus:** descubre las réplicas por DNS (`dns_sd_configs`), con un objetivo por
  réplica. Como una réplica muerta deja de ser objetivo, `up == 0` no avisa si mueren
  todas; para eso está la alerta `ServicioSinReplicas`.
- **Prueba:** `pruebas/e2e/replicas-y-circuito.py`, con carga continua (unas 9
  solicitudes por segundo):
  - **Cae la réplica de Persistencia que atiende la carga** (`docker kill`): 0 de 173
    solicitudes fallan, la más lenta tarda 2,6 s y el circuito no se abre.
  - **Cae la réplica de Reportes que atiende la carga:** 0 de 134 fallan y la más lenta
    tarda 2,1 s.
  - **Cae toda Persistencia:** Reportes (Java) e Indicadores (.NET) responden 503 y abren
    el circuito. Antes de abrirse, cada par de solicitudes tardaba hasta 0,7 s; abierto,
    12 ms. Al volver Persistencia, el circuito se cierra solo.
  - **Dos relays del outbox:** 10 reportes abren 10 casos, sin duplicados ni eventos
    pendientes.
  - **Las 7 suites e2e existentes** también pasan con 2 réplicas de cada uno.

## Alternativas descartadas
- **Circuit breaker escrito a mano:** habría que mantener la misma lógica en dos
  lenguajes y cinco servicios. Resilience4j y Polly son las librerías estándar de cada
  plataforma.
- **Balanceador (nginx/HAProxy) delante de cada servicio:** es un componente más que
  operar y, si no se replica, otro punto único de falla. El DNS de Docker basta para el
  compose. En Kubernetes lo haría el Service.
- **Réplicas por defecto en el compose (`deploy.replicas`):** el servidor de pruebas
  tiene 8 GB y no necesita alta disponibilidad. Las réplicas se piden con `--scale`
  cuando se quieren.

## Consecuencias
- **El reparto es por conexión, no por solicitud.** Los clientes reutilizan conexiones
  abiertas, así que durante un rato una réplica puede recibir más carga que la otra.
  Con más tráfico, o al renovarse las conexiones, se equilibra.
- **Latencia al caer una réplica.** Mientras el DNS en caché aún incluye la réplica caída
  (hasta 5 s), una solicitud puede tardar unos 2 s más (el timeout de conexión) antes de
  ir a la otra. En la prueba no falló ninguna, pero con mucho más tráfico en esa ventana
  el circuito podría abrirse unos segundos aunque la otra réplica responda.
- **Abierto, el circuito también rechaza lo que habría funcionado** durante 15 s. Es el
  precio de no seguir cargando a un servicio caído.
- **PostgreSQL, RabbitMQ, el gateway y el almacén siguen siendo instancias únicas** en el
  compose. Supabase (ADR-19) ya aporta su propia disponibilidad para la base.
