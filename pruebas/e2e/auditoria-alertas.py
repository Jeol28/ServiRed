#!/usr/bin/env python3
"""
Prueba de punta a punta de la fase 7 (RF-12, RF-13) contra el entorno de Docker
Compose, a través del gateway:

  1. Acciones administrativas, accesos, reportes, cambios de caso (con actor) y
     exportaciones quedan en el registro de auditoría, sin datos personales.
  2. Seguridad: 5 inicios de sesión fallidos sobre una cuenta y la reutilización
     de un token de refresco generan alertas.
  3. Solo regulador y administrador consultan; solo el administrador verifica
     la integridad; el registro es de solo inserción y su cadena es íntegra.
  4. Con el perfil observabilidad: Prometheus raspa todos los servicios, la
     alerta de seguridad y la de cola de fallidos llegan por Alertmanager a la
     auditoría, y Jaeger recibe trazas.

Uso (desde la raíz, con el compose arriba; el bloque 4 requiere
`TRAZAS_ACTIVAS=true docker compose --profile observabilidad up -d`):
    python3 pruebas/e2e/auditoria-alertas.py
"""
import base64
import json
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

from comun import (ENV, RABBIT_API, comprobar, compose, psql, correo_nuevo, entrar_como_admin, esperar, http, http_crudo,
                   registrar_y_entrar, sql, terminar)

CLAVE = "una clave larga de prueba"
MARCA = f"marca-auditoria-{uuid.uuid4().hex[:8]}"
COLOMBIA = timezone(timedelta(hours=-5))


def personal(admin, rol, empresa=None):
    correo = correo_nuevo(rol.lower())
    cuerpo = {"correo": correo, "clave": CLAVE, "rol": rol, **({"idEmpresa": empresa} if empresa else {})}
    cuenta = http("POST", "/api/v1/cuentas", cuerpo, token=admin)[2]
    return correo, cuenta["idCuenta"], http("POST", "/api/v1/auth/login", {"correo": correo, "clave": CLAVE})[2]


def registros(token, **filtros):
    q = "&".join(f"{k}={v}" for k, v in filtros.items())
    estado, _, p = http("GET", f"/api/v1/auditoria/registros?tamano=200&{q}", token=token)
    return p["elementos"] if estado == 200 else []


def hay(token, **filtros):
    return lambda: bool(registros(token, **filtros))


def prometheus(ruta):
    with urllib.request.urlopen("http://127.0.0.1:9090" + ruta, timeout=10) as r:
        return json.loads(r.read())


try:
    urllib.request.urlopen("http://127.0.0.1:9090/-/ready", timeout=5).read()
    con_observabilidad = True
except Exception:  # noqa: BLE001 - el perfil es opcional
    con_observabilidad = False

if con_observabilidad:
    # Prometheus debe haber visto las series de alertas en 0 antes de que suban (increase()).
    esperar(lambda: bool(prometheus("/api/v1/query?query=servired_alertas_seguridad_total")["data"]["result"]), 60)

sesion_admin = entrar_como_admin()
admin, id_admin = sesion_admin["tokenAcceso"], sesion_admin["cuenta"]["idCuenta"]

print("1) Acciones administrativas y accesos (RF-12)")
correo_op, id_op, sesion_op = personal(admin, "OPERADOR", "a0000000-0000-4000-8000-000000000001")
operador = sesion_op["tokenAcceso"]
correo_reg, id_reg, sesion_reg = personal(admin, "REGULADOR")
regulador = sesion_reg["tokenAcceso"]
comprobar(esperar(hay(admin, accion="CUENTA_CREADA", recursoId=id_op)), "CUENTA_CREADA del operador")
r = registros(admin, accion="CUENTA_CREADA", recursoId=id_op)[0]
comprobar(r.get("actorId") == id_admin and r.get("actorRol") == "ADMINISTRADOR", "con el administrador como actor")
comprobar(correo_op not in json.dumps(r), "sin el correo de la cuenta creada")
comprobar(esperar(hay(admin, accion="INICIO_SESION", actorId=id_op)), "INICIO_SESION del operador")

print("2) Reportes, casos y exportaciones")
ciudadano = registrar_y_entrar()[0]["tokenAcceso"]
inicio = (datetime.now(COLOMBIA) - timedelta(hours=1)).isoformat(timespec="seconds")
reporte = http("POST", "/api/v1/reportes", {"tipoServicio": "ENERGIA", "ubicacion": {"latitud": 4.6287, "longitud": -74.0648},
                                            "inicioEstimado": inicio, "magnitud": "CUADRA",
                                            "descripcion": f"Sin luz {MARCA}"}, token=ciudadano)[2]
comprobar(esperar(hay(admin, accion="REPORTE_CREADO", recursoId=reporte["idReporte"])), "REPORTE_CREADO registrado")
comprobar(MARCA not in json.dumps(registros(admin, recursoId=reporte["idReporte"])), "sin la descripción del ciudadano")


def caso():
    p = http("GET", "/api/v1/casos?tamano=100", token=operador)[2]
    return next((c for c in p["elementos"] if c["idReporte"] == reporte["idReporte"]), None)


comprobar(esperar(lambda: caso() is not None), "Atención abre el caso")
http("POST", f"/api/v1/casos/{caso()['idCaso']}/atender", token=operador)
comprobar(esperar(hay(admin, accion="CASO_ACTUALIZADO", actorId=id_op)), "CASO_ACTUALIZADO con el operador como actor")
http_crudo("GET", "/api/v1/indicadores/exportaciones/reportes?formato=csv&codigoMunicipio=11001", None, None, regulador)
comprobar(esperar(hay(admin, accion="EXPORTACION_REGULATORIA", actorId=id_reg)), "EXPORTACION_REGULATORIA del regulador")

print("3) Alertas de seguridad (RF-13)")
for _ in range(5):
    http("POST", "/api/v1/auth/login", {"correo": correo_op, "clave": "no es la clave correcta"})
comprobar(esperar(hay(admin, accion="INICIO_SESION_FALLIDO", recursoId=id_op)), "INICIO_SESION_FALLIDO sobre la cuenta")
comprobar(esperar(lambda: any(id_op in (a.get("recursoId") or "")
                              for a in registros(admin, categoria="ALERTA", accion="FUERZA_BRUTA_CUENTA"))),
          "alerta FUERZA_BRUTA_CUENTA sobre la cuenta")
fallido = registros(admin, accion="INICIO_SESION_FALLIDO", recursoId=id_op)[0]
comprobar(fallido.get("actorId") is None and correo_op not in json.dumps(fallido), "el fallo no registra correo ni actor")
refresco = sesion_reg["tokenRefresco"]
http("POST", "/api/v1/auth/refrescar", {"tokenRefresco": refresco})
http("POST", "/api/v1/auth/refrescar", {"tokenRefresco": refresco})
comprobar(esperar(hay(admin, accion="REFRESCO_REUTILIZADO")), "REFRESCO_REUTILIZADO registrado")
comprobar(esperar(hay(admin, categoria="ALERTA", accion="TOKEN_REUTILIZADO")), "alerta TOKEN_REUTILIZADO")

print("4) Acceso e integridad del registro")
comprobar(http("GET", "/api/v1/auditoria/registros", token=operador)[0] == 403, "el operador no consulta la auditoría")
comprobar(http("GET", "/api/v1/auditoria/registros")[0] == 401, "sin token -> 401")
comprobar(bool(registros(entrar_como_admin()["tokenAcceso"], categoria="ACCESO")), "el administrador consulta")
comprobar(http("GET", "/api/v1/auditoria/integridad", token=http("POST", "/api/v1/auth/login",
          {"correo": correo_reg, "clave": CLAVE})[2]["tokenAcceso"])[0] == 403, "el regulador no verifica la integridad")
estado, _, v = http("GET", "/api/v1/auditoria/integridad", token=admin)
comprobar(estado == 200 and v["integra"] is True and v["registros"] > 0, f"cadena íntegra ({v.get('registros')} registros)")
intento = psql("UPDATE auditoria.registros SET accion = 'X' WHERE secuencia = 1")
comprobar("solo inserción" in intento.stderr and intento.returncode != 0,
          "un UPDATE directo en la base de datos es rechazado (solo inserción)")
comprobar(http("POST", "/api/v1/auditoria/registros", {}, token=admin)[0] in (401, 403, 405), "no se escribe por el gateway")


if not con_observabilidad:
    print("5) Observabilidad: perfil no activo, se omite")
else:
    print("5) Observabilidad: Prometheus, Alertmanager -> Auditoría y Jaeger")
    objetivos = prometheus("/api/v1/targets")["data"]["activeTargets"]
    comprobar(len(objetivos) == 8 and all(t["health"] == "up" for t in objetivos), "Prometheus raspa los 8 objetivos")
    comprobar(esperar(lambda: any(a["labels"]["alertname"] == "AlertaDeSeguridadDetectada"
                                  for a in prometheus("/api/v1/alerts")["data"]["alerts"]), 90),
              "Prometheus dispara AlertaDeSeguridadDetectada")
    comprobar(esperar(hay(admin, categoria="ALERTA", accion="AlertaDeSeguridadDetectada"), 90),
              "Alertmanager la entrega a Auditoría")
    # Un mensaje ilegible termina en la DLQ de Atención -> alerta operativa.
    auth = base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()
    pub = urllib.request.Request(f"{RABBIT_API}/exchanges/%2F/servired.eventos/publish", method="POST",
                                 headers={"Authorization": f"Basic {auth}", "content-type": "application/json"},
                                 data=json.dumps({"properties": {"delivery_mode": 2}, "routing_key": "reporte.creado.v1",
                                                  "payload": "no es json", "payload_encoding": "string"}).encode())
    urllib.request.urlopen(pub, timeout=10).read()
    comprobar(esperar(hay(admin, categoria="ALERTA", accion="MensajesEnColaDeFallidos"), 180),
              "MensajesEnColaDeFallidos llega a Auditoría")
    for cola in ["atencion.eventos-reportes.dlq", "indicadores.eventos.dlq", "auditoria.eventos.dlq"]:
        urllib.request.urlopen(urllib.request.Request(f"{RABBIT_API}/queues/%2F/{cola}/contents", method="DELETE",
                                                      headers={"Authorization": f"Basic {auth}"}), timeout=10).read()
    with urllib.request.urlopen("http://127.0.0.1:16686/api/services", timeout=10) as r:
        servicios = set(json.loads(r.read())["data"] or [])
    comprobar({"gateway", "reportes", "persistencia", "atencion"} <= servicios, f"Jaeger recibe trazas ({sorted(servicios)})")

terminar()
