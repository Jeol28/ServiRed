#!/usr/bin/env python3
"""
Contrato de eventos de punta a punta: cada mensaje que circula por RabbitMQ cumple el
AsyncAPI y su JSON Schema, sea de un productor Java (Reportes, Identidad) o .NET
(Atención, Indicadores).

  1. Una cola temporal se enlaza a «#» en servired.eventos y escucha todo el tráfico.
  2. Se recorren flujos que producen los 7 tipos de evento desde sus servicios reales:
     reporte, validación, restablecimiento, denuncia (con y sin foto), atención y cierre
     del caso, moderación, exportación y accesos.
  3. Cada mensaje capturado se valida contra el contrato (sobre + datos, formatos uuid y
     date-time, routing key) y sus propiedades AMQP (persistente, JSON, messageId).
  4. Se comprueba que aparecieron todos los tipos y todos los productores esperados.

Requiere el compose arriba. La validación corre en un contenedor con jsonschema
(pruebas/contratos/verificar-contratos.sh).
Uso (desde la raíz):  python3 pruebas/e2e/contrato-eventos.py
"""
import base64
import json
import os
import subprocess
import tempfile
import time
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

from comun import (ENV, RABBIT_API, RAIZ, comprobar, correo_nuevo, entrar_como_admin, esperar, http, http_crudo,
                   multipart, png_con_metadatos, registrar_y_entrar, terminar)

ENERGIA_ANDINA = "a0000000-0000-4000-8000-000000000001"
BOGOTA = {"latitud": 4.6287, "longitud": -74.0648}
LETICIA = {"latitud": -4.2153, "longitud": -69.9406}         # sin empresa con cobertura
CERCA = {"latitud": 4.6300, "longitud": -74.0650}
COLOMBIA = timezone(timedelta(hours=-5))
CLAVE = "una clave larga de prueba"
COLA = f"e2e.contrato-{uuid.uuid4().hex[:8]}"

# Tipo -> servicios que deben haberlo producido en este recorrido.
ESPERADOS = {
    "reporte.creado": {"reportes"},
    "validacion.registrada": {"reportes"},
    "restablecimiento.registrado": {"reportes"},
    "denuncia.recibida": {"reportes"},
    "caso.actualizado": {"atencion"},
    "caso.cerrado": {"atencion"},
    "accion.auditada": {"identidad", "atencion", "indicadores"},
}


def rabbit(metodo, ruta, cuerpo=None):
    auth = base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()
    peticion = urllib.request.Request(f"{RABBIT_API}{ruta}", method=metodo,
                                      headers={"Authorization": f"Basic {auth}", "content-type": "application/json"},
                                      data=json.dumps(cuerpo).encode() if cuerpo is not None else None)
    with urllib.request.urlopen(peticion, timeout=15) as r:
        contenido = r.read()
        return json.loads(contenido) if contenido else None


def hace(horas):
    return (datetime.now(COLOMBIA) - timedelta(hours=horas)).isoformat(timespec="seconds")


def personal(admin, rol, empresa=None):
    correo = correo_nuevo(rol.lower())
    cuerpo = {"correo": correo, "clave": CLAVE, "rol": rol, **({"idEmpresa": empresa} if empresa else {})}
    estado, _, cuenta = http("POST", "/api/v1/cuentas", cuerpo, token=admin)
    assert estado == 201, (estado, cuenta)
    return http("POST", "/api/v1/auth/login", {"correo": correo, "clave": CLAVE})[2]["tokenAcceso"]


def caso_de(token, id_reporte, filtro=""):
    estado, _, pagina = http("GET", f"/api/v1/casos?tamano=100{filtro}", token=token)
    return next((c for c in pagina["elementos"] if c["idReporte"] == id_reporte), None) if estado == 200 else None


print("1. Cola de escucha")
rabbit("PUT", f"/queues/%2F/{COLA}", {"durable": False, "auto_delete": False,
                                      "arguments": {"x-queue-type": "classic", "x-expires": 900_000}})
rabbit("POST", f"/bindings/%2F/e/servired.eventos/q/{COLA}", {"routing_key": "#"})
comprobar(True, f"{COLA} escucha todo servired.eventos")

try:
    print("2. Flujos que producen los 7 tipos de evento")
    admin = entrar_como_admin()["tokenAcceso"]                                   # accion.auditada (identidad)
    operador = personal(admin, "OPERADOR", ENERGIA_ANDINA)
    regulador = personal(admin, "REGULADOR")
    http("POST", "/api/v1/auth/login", {"correo": correo_nuevo(), "clave": "una clave que no es"})  # acceso fallido
    autor = registrar_y_entrar()[0]["tokenAcceso"]
    vecino = registrar_y_entrar()[0]["tokenAcceso"]

    estado, _, r = http("POST", "/api/v1/reportes", {                             # reporte.creado
        "tipoServicio": "ENERGIA", "ubicacion": BOGOTA, "inicioEstimado": hace(2), "magnitud": "CUADRA",
        "descripcion": "Sin luz en la cuadra (contrato de eventos)"}, correlacion=str(uuid.uuid4()), token=autor)
    comprobar(estado == 201, f"reporte creado ({estado})")
    id_reporte = r["idReporte"]
    estado, _, _ = http("POST", "/api/v1/reportes", {                             # reporte.creado sin descripción
        "tipoServicio": "ACUEDUCTO", "ubicacion": BOGOTA, "inicioEstimado": hace(1), "magnitud": "VIVIENDA"},
        correlacion=str(uuid.uuid4()), token=autor)
    comprobar(estado == 201, "reporte mínimo (sin descripción) creado")
    estado, _, _ = http("POST", f"/api/v1/reportes/{id_reporte}/validaciones",     # validacion.registrada
                        {"objeto": "CORTE", "sentido": "CONFIRMA", "ubicacion": CERCA}, token=vecino)
    comprobar(estado == 201, f"validación registrada ({estado})")

    comprobar(esperar(lambda: caso_de(operador, id_reporte) is not None, 30), "Atención abrió el caso")
    id_caso = caso_de(operador, id_reporte)["idCaso"]
    comprobar(http("POST", f"/api/v1/casos/{id_caso}/atender", token=operador)[0] == 200,
              "el operador atiende el caso")                                        # caso.actualizado
    comprobar(http("POST", f"/api/v1/casos/{id_caso}/resolver", {"resolucion": "Se cambió el fusible"},
                   token=operador)[0] == 200, "el operador lo resuelve")
    estado, _, _ = http("POST", f"/api/v1/reportes/{id_reporte}/restablecimiento",  # restablecimiento.registrado
                        {"fechaRestablecimiento": hace(0.5), "comentario": "Volvió la luz"}, token=autor)
    comprobar(estado == 200, f"restablecimiento registrado ({estado})")
    comprobar(http("POST", f"/api/v1/casos/{id_caso}/cerrar", {}, token=operador)[0] == 200,
              "el operador cierra el caso")                                         # caso.cerrado

    # Un caso SIN empresa: su caso.actualizado no lleva idEmpresaOperadora (antes salía null).
    estado, _, sin = http("POST", "/api/v1/reportes", {
        "tipoServicio": "ENERGIA", "ubicacion": LETICIA, "inicioEstimado": hace(1), "magnitud": "VIVIENDA"},
        correlacion=str(uuid.uuid4()), token=autor)
    comprobar(estado == 201, f"reporte en Leticia (sin empresa con cobertura) ({estado})")
    LETICIA_SIN_EMPRESA = "&sinEmpresa=true&codigoMunicipio=91001"
    comprobar(esperar(lambda: caso_de(admin, sin["idReporte"], LETICIA_SIN_EMPRESA) is not None, 30),
              "su caso se abrió sin empresa")
    id_caso_sin = caso_de(admin, sin["idReporte"], LETICIA_SIN_EMPRESA)["idCaso"]
    comprobar(http("POST", f"/api/v1/casos/{id_caso_sin}/atender", token=admin)[0] == 200,
              "el administrador atiende el caso sin empresa")         # caso.actualizado sin empresa

    denuncia = {"tipoServicio": "ENERGIA", "ubicacion": BOGOTA,
                "descripcion": "Conexión directa al poste (contrato de eventos)"}
    cuerpo, tipo = multipart([("denuncia", None, "application/json", json.dumps(denuncia).encode()),
                              ("foto", "f.png", "image/png", png_con_metadatos())])
    estado, _, d = http_crudo("POST", "/api/v1/denuncias", cuerpo, tipo)           # denuncia.recibida con foto
    comprobar(estado == 202, f"denuncia con foto recibida ({estado})")
    id_denuncia = json.loads(d)["idDenuncia"]
    cuerpo, tipo = multipart([("denuncia", None, "application/json", json.dumps(denuncia).encode())])
    comprobar(http_crudo("POST", "/api/v1/denuncias", cuerpo, tipo)[0] == 202, "denuncia sin foto recibida")

    def moderable():
        return http("GET", f"/api/v1/moderacion/denuncias/{id_denuncia}", token=operador)[0] == 200
    comprobar(esperar(moderable, 30), "la denuncia llegó a moderación")
    estado, _, _ = http("POST", f"/api/v1/moderacion/denuncias/{id_denuncia}/decision",  # accion.auditada (atencion)
                        {"decision": "VALIDADA", "nota": "Verificada (contrato de eventos)"}, token=operador)
    comprobar(estado == 200, f"denuncia moderada ({estado})")
    estado, _, _ = http_crudo("GET", "/api/v1/indicadores/exportaciones/reportes?formato=csv",  # accion.auditada (indicadores)
                              None, None, regulador)
    comprobar(estado == 200, f"exportación regulatoria ({estado})")

    print("3. Mensajes capturados")
    capturados = []

    def recoger():
        for m in rabbit("POST", f"/queues/%2F/{COLA}/get",
                        {"count": 500, "ackmode": "ack_requeue_false", "encoding": "auto"}) or []:
            if m["routing_key"] != "sonda.v1":   # la sonda de antigüedad (ADR-21) no es un evento de negocio
                capturados.append(m)
        tipos = {json.loads(m["payload"]).get("tipo") for m in capturados}
        return set(ESPERADOS) <= tipos and any('"caso.cerrado"' in m["payload"] for m in capturados)
    esperar(recoger, 40)
    time.sleep(3)
    recoger()
    print(f"     {len(capturados)} eventos capturados")
finally:
    rabbit("DELETE", f"/queues/%2F/{COLA}")

vistos = {}
for m in capturados:
    try:
        sobre = json.loads(m["payload"])
    except json.JSONDecodeError:
        comprobar(False, f"mensaje no es JSON en {m['routing_key']}")
        continue
    vistos.setdefault(sobre.get("tipo"), set()).add(sobre.get("origen"))
for tipo, productores in ESPERADOS.items():
    comprobar(productores <= vistos.get(tipo, set()),
              f"{tipo}: producido por {sorted(vistos.get(tipo, set()))} (se esperaba {sorted(productores)})")

print("4. Propiedades AMQP (AsyncAPI: persistentes, JSON, messageId = idEvento)")
malas = []
for m in capturados:
    p, sobre = m["properties"], json.loads(m["payload"])
    if p.get("delivery_mode") != 2 or p.get("content_type") != "application/json" \
            or p.get("message_id") != sobre.get("idEvento"):
        malas.append((m["routing_key"], p.get("delivery_mode"), p.get("content_type"), p.get("message_id")))
comprobar(not malas, f"todas las propiedades cumplen ({malas[:3]})")

print("5. Cada evento contra su esquema")
with tempfile.TemporaryDirectory(dir=RAIZ / "pruebas") as carpeta:
    ruta = os.path.join(carpeta, "mensajes.json")
    with open(ruta, "w", encoding="utf-8") as f:
        json.dump([{"routing_key": m["routing_key"], "sobre": json.loads(m["payload"])} for m in capturados], f)
    relativa = os.path.relpath(ruta, RAIZ)
    salida = subprocess.run(["sh", "pruebas/contratos/verificar-contratos.sh", "pruebas/contratos/validar-captura.py",
                             relativa], cwd=RAIZ, capture_output=True, text=True)
    resultado = json.loads(salida.stdout.strip().splitlines()[-1]) if salida.returncode in (0, 1) and salida.stdout \
        else {"error": salida.stderr[-500:]}
if "error" in resultado:
    comprobar(False, f"no se pudo validar: {resultado['error']}")
else:
    for tipo, cuantos in sorted(resultado["validos"].items()):
        print(f"     {tipo}: {cuantos} válidos")
    for invalido in resultado["invalidos"]:
        print(f"     INVÁLIDO {invalido['routing_key']} ({invalido['origen']}): {invalido['errores'][:3]}")
    comprobar(not resultado["invalidos"] and sum(resultado["validos"].values()) == len(capturados),
              f"los {len(capturados)} eventos cumplen el AsyncAPI y su JSON Schema "
              f"({len(resultado['invalidos'])} inválidos)")

terminar()
