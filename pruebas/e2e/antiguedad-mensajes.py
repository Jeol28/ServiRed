#!/usr/bin/env python3
"""
Antigüedad de los mensajes (RNF-R3, ADR-21) contra el compose:

  outbox (edad exacta del evento sin publicar más viejo, en Persistencia)
  cola   (cota superior con la sonda: cada consumidor mide desde la última sonda consumida)

Verifica:
  1. Persistencia publica la sonda y cada consumidor (Atención, Reportes, Indicadores)
     informa la edad del mensaje más viejo de su cola; en reposo, unos segundos.
  2. Con Atención detenida, su cola envejece: al volver, la sonda que esperó mide la espera
     (> 10 s) y el reporte llega igual al operador.
  3. Con RabbitMQ detenido, el evento del reporte espera en el outbox y su edad se mide;
     al volver RabbitMQ se publica y la edad vuelve a 0.

Detiene contenedores: úsela solo en un entorno de pruebas.
Uso (desde la raíz):  python3 pruebas/e2e/antiguedad-mensajes.py
"""
import json
import subprocess
import time
import uuid
from datetime import datetime, timedelta, timezone

from comun import comprobar, compose, esperar, http, registrar_y_entrar, sql, terminar

CONSUMIDORES = {
    "atencion": ("atencion.eventos-reportes", "/metrics"),
    "reportes": ("reportes.eventos-casos", "/actuator/prometheus"),
    "indicadores": ("indicadores.eventos", "/metrics"),
}


def contenedores(servicio):
    salida = compose("ps", "--format", "json", servicio).stdout.strip()
    filas = json.loads(salida) if salida.startswith("[") else [json.loads(l) for l in salida.splitlines() if l]
    return sorted(f["Name"] for f in filas if f.get("State") == "running")


def muestras(servicio, ruta, nombre, **etiquetas):
    """Valores de una métrica en todas las réplicas del servicio."""
    valores = []
    for c in contenedores(servicio):
        texto = subprocess.run(["docker", "exec", c, "curl", "-fsS", f"http://localhost:8080{ruta}"],
                               capture_output=True, text=True, check=False).stdout
        for linea in texto.splitlines():
            if linea.startswith(nombre + "{") or linea.startswith(nombre + " "):
                if all(f'{k}="{v}"' in linea for k, v in etiquetas.items()):
                    valores.append(float(linea.rsplit(" ", 1)[1]))
    return valores


def edad_cola(servicio):
    cola, ruta = CONSUMIDORES[servicio]
    valores = muestras(servicio, ruta, "servired_cola_mensaje_mas_viejo_segundos", cola=cola)
    return min(valores) if valores else None


def edad_outbox():
    valores = muestras("persistencia", "/actuator/prometheus", "servired_outbox_mensaje_mas_viejo_segundos")
    return max(valores) if valores else None


def sano(servicio):
    return all(subprocess.run(["docker", "inspect", "-f", "{{.State.Health.Status}}", c], capture_output=True,
                              text=True).stdout.strip() == "healthy" for c in contenedores(servicio)) \
        and bool(contenedores(servicio))


def nuevo_reporte():
    inicio = datetime.now(timezone(timedelta(hours=-5))) - timedelta(hours=1)
    return {"tipoServicio": "GAS", "ubicacion": {"latitud": 4.6287, "longitud": -74.0648},
            "inicioEstimado": inicio.isoformat(timespec="seconds"), "magnitud": "VIVIENDA",
            "descripcion": "Sin gas (prueba de antigüedad)"}


print("1. La sonda llega a todas las colas")
ultima = muestras("persistencia", "/actuator/prometheus", "servired_sonda_ultima_publicacion_segundos")
comprobar(ultima and min(ultima) < 5, f"Persistencia publica la sonda (última hace {min(ultima or [99]):.1f} s)")
for servicio in CONSUMIDORES:
    comprobar(esperar(lambda s=servicio: (edad_cola(s) or 99) < 5, 20),
              f"{servicio}: el mensaje más viejo de {CONSUMIDORES[servicio][0]} tiene "
              f"{edad_cola(servicio)} s (en reposo, < 5 s)")
comprobar((edad_outbox() or 0) < 2, f"outbox al día (edad del más viejo: {edad_outbox()} s)")

print("2. Atención detenida: su cola envejece y se mide al volver")
sesion, _ = registrar_y_entrar()
token = sesion["tokenAcceso"]
cola, ruta = CONSUMIDORES["atencion"]
antes = sum(muestras("atencion", ruta, "servired_cola_espera_sonda_segundos_count", cola=cola))
compose("stop", "atencion")
estado, _, r = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion=str(uuid.uuid4()), token=token)
comprobar(estado == 201, f"con Atención detenida el reporte se acepta igual ({estado})")
time.sleep(15)
compose("start", "atencion")
comprobar(esperar(lambda: sano("atencion"), 90), "Atención volvió")
comprobar(esperar(lambda: sql(f"SELECT count(*) FROM atencion.casos WHERE id_reporte = '{r['idReporte']}'") == "1", 30),
          "el reporte llegó al operador al volver Atención")
# Tras reiniciar, el histograma empieza de cero: lo que mida es de esta vuelta.
lentas = muestras("atencion", ruta, "servired_cola_espera_sonda_segundos_count", cola=cola)
rapidas = muestras("atencion", ruta, "servired_cola_espera_sonda_segundos_bucket", cola=cola, le="10.0") \
    or muestras("atencion", ruta, "servired_cola_espera_sonda_segundos_bucket", cola=cola, le="10")
esperaron_mas_de_10 = sum(lentas) - sum(rapidas)
print(f"     sondas consumidas al volver: {sum(lentas):.0f}; esperaron más de 10 s: {esperaron_mas_de_10:.0f}")
comprobar(esperaron_mas_de_10 >= 1, "las sondas que esperaron en la cola midieron más de 10 s (RNF-R3 en riesgo)")
comprobar(esperar(lambda: (edad_cola("atencion") or 99) < 5, 20),
          f"al vaciarse la cola la edad vuelve a unos segundos ({edad_cola('atencion')} s)")

print("3. RabbitMQ detenido: el evento espera en el outbox y su edad se mide")
compose("stop", "rabbitmq")
estado, _, r = http("POST", "/api/v1/reportes", nuevo_reporte(), correlacion=str(uuid.uuid4()), token=token)
comprobar(estado == 201, f"sin RabbitMQ el reporte se acepta igual (outbox) ({estado})")
comprobar(esperar(lambda: (edad_outbox() or 0) > 10, 30),
          f"la edad del evento sin publicar supera 10 s ({edad_outbox()} s)")
compose("start", "rabbitmq")
comprobar(esperar(lambda: edad_outbox() == 0, 120), "al volver RabbitMQ el outbox se vacía y la edad vuelve a 0")
comprobar(esperar(lambda: sql(f"SELECT count(*) FROM atencion.casos WHERE id_reporte = '{r['idReporte']}'") == "1", 90),
          "y el reporte llega al operador")
comprobar(esperar(lambda: all((edad_cola(s) or 99) < 5 for s in CONSUMIDORES), 90),
          "todas las colas vuelven a estar al día")

terminar()
