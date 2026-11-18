#!/usr/bin/env python3
"""
Réplicas y circuit breaker (ADR-20) contra el compose con dos réplicas de
Persistencia y de Reportes:

  docker compose up -d --build --wait --scale persistencia=2 --scale reportes=2
  python3 pruebas/e2e/replicas-y-circuito.py

Verifica:
  1. Hay al menos dos réplicas de Persistencia y de Reportes, y el sistema responde.
  2. Con carga en curso cae (docker kill) la réplica de Persistencia que la atiende:
     la otra sigue atendiendo y el circuito no queda abierto. Luego vuelve a arrancar.
  3. Lo mismo con la réplica de Reportes que atiende la carga: el gateway sigue con la otra.
  4. Se cae TODA Persistencia: los servicios responden 503 y, con el circuito
     abierto, fallan al instante (Reportes en Java e Indicadores en .NET). Al volver
     Persistencia, el circuito se cierra solo y todo responde de nuevo.
  5. Con dos relays del outbox: cada reporte abre exactamente un caso y no queda
     ningún evento sin publicar.

Hace caer contenedores: úsela solo en un entorno de pruebas.
"""
import json
import subprocess
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone

from comun import comprobar, compose, esperar, http, registrar_y_entrar, sql, terminar


def docker(*argumentos):
    return subprocess.run(["docker", *argumentos], capture_output=True, text=True, stdin=subprocess.DEVNULL,
                          check=False)


def replicas(servicio):
    """Contenedores en ejecución del servicio, ordenados por nombre."""
    salida = compose("ps", "--format", "json", servicio).stdout.strip()
    filas = json.loads(salida) if salida.startswith("[") else [json.loads(l) for l in salida.splitlines() if l]
    return sorted(f["Name"] for f in filas if f.get("State") == "running")


def sano(contenedor):
    estado = docker("inspect", "-f", "{{.State.Health.Status}}", contenedor).stdout.strip()
    return estado == "healthy"


def metrica(contenedor, nombre, ruta="/actuator/prometheus", **etiquetas):
    """Suma de las muestras de una métrica en UNA réplica (se lee dentro del contenedor)."""
    texto = docker("exec", contenedor, "curl", "-fsS", f"http://localhost:8080{ruta}").stdout
    total = 0.0
    for linea in texto.splitlines():
        if not linea.startswith(nombre) or linea.startswith("#"):
            continue
        if all(f'{k}="{v}"' in linea for k, v in etiquetas.items()):
            total += float(linea.rsplit(" ", 1)[1])
    return total


def circuito_abierto_reportes(contenedor):
    return metrica(contenedor, "resilience4j_circuitbreaker_state", name="persistencia", state="open") == 1


def circuito_abierto_dotnet(contenedor):
    return metrica(contenedor, "servired_circuito_persistencia_estado", ruta="/metrics") == 1


class Carga:
    """Peticiones GET continuas en segundo plano; registra (segundo, estado, duración)."""

    def __init__(self, ruta, token=None, pausa=0.1):
        self.ruta, self.token, self.pausa = ruta, token, pausa
        self.resultados = []
        self._parar = threading.Event()
        self._hilo = threading.Thread(target=self._correr, daemon=True)
        self.inicio = time.time()

    def _correr(self):
        while not self._parar.is_set():
            t = time.time()
            try:
                estado, _, _ = http("GET", self.ruta, token=self.token, reintentar_429=False)
            except OSError:  # timeout del cliente (15 s) o conexión cortada: cuenta como falla
                estado = 0
            self.resultados.append((t - self.inicio, estado, time.time() - t))
            time.sleep(self.pausa)

    def __enter__(self):
        self._hilo.start()
        return self

    def __exit__(self, *_):
        self._parar.set()
        self._hilo.join(timeout=30)

    def fallas(self, desde=0.0):
        return [r for r in self.resultados if r[0] >= desde and r[1] != 200]


def solicitudes(contenedor):
    """Solicitudes HTTP atendidas por una réplica (contador de Spring)."""
    return metrica(contenedor, "http_server_requests_seconds_count")


def la_mas_cargada(servicio, antes):
    """La réplica que más solicitudes atendió desde `antes`: la que conviene hacer caer."""
    despues = {c: solicitudes(c) for c in replicas(servicio)}
    deltas = {c: despues[c] - antes.get(c, 0) for c in despues}
    return max(deltas, key=deltas.get), deltas


def caer_y_restaurar(servicio, ruta, token, segundos_estable):
    """Con carga en curso, hace caer (docker kill) la réplica que la está atendiendo y luego la
    vuelve a arrancar. docker kill cuenta como detención manual: Docker no la reinicia solo."""
    antes = {c: solicitudes(c) for c in replicas(servicio)}
    with Carga(ruta, token) as carga:
        time.sleep(3)
        victima, deltas = la_mas_cargada(servicio, antes)
        golpe = time.time() - carga.inicio
        docker("kill", victima)
        time.sleep(segundos_estable + 12)
    fallas = carga.fallas(desde=golpe)
    estables = carga.fallas(desde=golpe + segundos_estable)
    print(f"     carga por réplica antes de la caída: { {c[-1]: int(d) for c, d in deltas.items()} }; "
          f"cae {victima}")
    print(f"     {len(carga.resultados)} peticiones; {len(fallas)} fallaron tras la caída "
          f"(estados: {sorted({f[1] for f in fallas})})")
    comprobar(deltas[victima] > 0, f"la réplica que cae estaba atendiendo carga ({int(deltas[victima])} solicitudes)")
    comprobar(len(carga.resultados) > 50, "hubo carga continua durante la caída")
    comprobar(not estables, f"{segundos_estable} s después de la caída todo responde con la otra réplica "
                            f"({len(estables)} fallas)")
    mas_lenta = max(r[2] for r in carga.resultados)
    comprobar(mas_lenta < 12, f"ninguna solicitud quedó colgada (la más lenta tardó {mas_lenta:.1f} s)")
    docker("start", victima)
    comprobar(esperar_replicas_sanas(servicio), f"{victima} volvió a arrancar y está sana")


def esperar_replicas_sanas(servicio, cuantas=2, segundos=150):
    return esperar(lambda: len(replicas(servicio)) >= cuantas and all(sano(c) for c in replicas(servicio)), segundos)


def nuevo_reporte():
    inicio = datetime.now(timezone(timedelta(hours=-5))) - timedelta(hours=1)
    return {"tipoServicio": "ACUEDUCTO", "ubicacion": {"latitud": 4.6287, "longitud": -74.0648},
            "inicioEstimado": inicio.isoformat(timespec="seconds"), "magnitud": "CUADRA",
            "descripcion": "Sin agua (prueba de réplicas)"}


print("1. Réplicas en marcha")
persistencias, reporteses = replicas("persistencia"), replicas("reportes")
if not comprobar(len(persistencias) >= 2 and len(reporteses) >= 2,
                 f"dos réplicas o más: persistencia={len(persistencias)}, reportes={len(reporteses)}"):
    print("  Levante el compose con --scale persistencia=2 --scale reportes=2")
    terminar()
sesion, _ = registrar_y_entrar()
token = sesion["tokenAcceso"]
estado, _, _ = http("GET", "/api/v1/reportes", token=token)
comprobar(estado == 200, f"el sistema responde con réplicas ({estado})")

print("2. Cae la réplica de Persistencia que atiende la carga")
caer_y_restaurar("persistencia", "/api/v1/reportes", token, segundos_estable=8)
comprobar(not any(circuito_abierto_reportes(c) for c in replicas("reportes")),
          "el circuito de Reportes no quedó abierto (la otra réplica de Persistencia responde)")

print("3. Cae la réplica de Reportes que atiende la carga")
caer_y_restaurar("reportes", "/api/v1/reportes", token, segundos_estable=5)

print("4. Cae TODA Persistencia: el circuito se abre y se falla rápido")
compose("stop", "persistencia")
inicio = time.time()
lentas = []
while time.time() - inicio < 40:
    t = time.time()
    e1, _, cuerpo = http("GET", "/api/v1/reportes", token=token, reintentar_429=False)
    e2, _, _ = http("GET", "/api/v1/indicadores/resumen", reintentar_429=False)
    lentas.append((time.time() - t, e1, e2))
    if any(circuito_abierto_reportes(c) for c in replicas("reportes")) and \
            all(circuito_abierto_dotnet(c) for c in replicas("indicadores")) and len(lentas) > 5:
        break
comprobar(all(e1 == 503 and e2 == 503 for _, e1, e2 in lentas), "sin Persistencia, Reportes e Indicadores responden 503")
comprobar(any(circuito_abierto_reportes(c) for c in replicas("reportes")), "se abrió el circuito en Reportes (Java)")
comprobar(all(circuito_abierto_dotnet(c) for c in replicas("indicadores")), "se abrió el circuito en Indicadores (.NET)")
rapidas = []
for _ in range(5):
    t = time.time()
    e1, _, cuerpo = http("GET", "/api/v1/reportes", token=token, reintentar_429=False)
    e2, _, _ = http("GET", "/api/v1/indicadores/resumen", reintentar_429=False)
    rapidas.append(time.time() - t)
print(f"     antes de abrir: hasta {max(d for d, _, _ in lentas):.2f} s por par de peticiones; "
      f"con el circuito abierto: {max(rapidas) * 1000:.0f} ms")
comprobar(max(rapidas) < 0.5, "con el circuito abierto cada par de peticiones falla en menos de 0,5 s")
comprobar(e1 == 503 and (cuerpo or {}).get("status") == 503, "Reportes responde problem+json 503 con el circuito abierto")

print("   Vuelve Persistencia")
compose("start", "persistencia")
comprobar(esperar_replicas_sanas("persistencia"), "Persistencia volvió con sus dos réplicas")
comprobar(esperar(lambda: http("GET", "/api/v1/reportes", token=token, reintentar_429=False)[0] == 200
                  and http("GET", "/api/v1/indicadores/resumen", reintentar_429=False)[0] == 200, 60),
          "tras la espera del circuito, Reportes e Indicadores responden de nuevo")
for _ in range(10):  # tráfico para que cada réplica pruebe y cierre su circuito
    http("GET", "/api/v1/reportes", token=token, reintentar_429=False)
    http("GET", "/api/v1/indicadores/resumen", reintentar_429=False)
comprobar(esperar(lambda: not any(circuito_abierto_reportes(c) for c in replicas("reportes")), 30),
          "el circuito de Reportes se cerró solo")

print("5. Dos relays del outbox: un caso por reporte, nada pendiente")
ids = []
for _ in range(10):
    estado, _, r = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion=str(uuid.uuid4()), token=token)
    if estado == 201:
        ids.append(r["idReporte"])
comprobar(len(ids) == 10, f"se crearon los 10 reportes ({len(ids)})")
lista = ",".join(f"'{i}'" for i in ids)
comprobar(esperar(lambda: sql(f"SELECT count(*) FROM atencion.casos WHERE id_reporte IN ({lista})") == "10", 60),
          "cada reporte abrió su caso")
comprobar(sql(f"SELECT count(*) FROM (SELECT id_reporte FROM atencion.casos WHERE id_reporte IN ({lista}) "
              "GROUP BY id_reporte HAVING count(*) > 1) d") == "0", "ningún reporte abrió dos casos")
comprobar(esperar(lambda: sql("SELECT count(*) FROM infraestructura.outbox WHERE publicado_en IS NULL") == "0", 30),
          "no quedó ningún evento sin publicar en el outbox")

terminar()
