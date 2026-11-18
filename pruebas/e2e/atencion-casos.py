#!/usr/bin/env python3
"""
Prueba de punta a punta de la fase 5 (RF-5 a RF-7, ADR-07) contra el entorno de
Docker Compose, siempre a través del gateway:

  1. reporte.creado -> Atención abre el caso enrutado a la empresa con cobertura
     y priorizado; otra empresa no lo ve.
  2. La validación comunitaria del corte sube la prioridad.
  3. El operador atiende, resuelve y cierra; el ciudadano ve EN_ATENCION,
     RESTABLECIDO y CERRADO en su reporte (eventos caso.*), y aún puede registrar
     la hora real del restablecimiento.
  4. Si el ciudadano registra el restablecimiento primero, el caso se resuelve solo.
  5. El regulador consulta pero no gestiona; un caso sin cobertura lo enruta un
     administrador; solo se asigna a operadores de la empresa del caso.
  6. Moderación de denuncias anónimas por la empresa con cobertura.
  7. Ninguna cola de mensajes fallidos recibió nada.

Uso (desde la raíz, con el compose arriba):  python3 pruebas/e2e/atencion-casos.py
"""
import json
from datetime import datetime, timedelta, timezone

from comun import (buscar_caso, comprobar, correo_nuevo, entrar_como_admin, esperar, http, http_crudo, mensajes_en_cola,
                   multipart, png_con_metadatos, registrar_y_entrar, terminar)

ENERGIA_ANDINA = "a0000000-0000-4000-8000-000000000001"   # ENERGIA en Bogotá, Medellín, Bucaramanga
AGUAS_SABANA = "a0000000-0000-4000-8000-000000000002"
BOGOTA = {"latitud": 4.6287, "longitud": -74.0648}
CERCA = {"latitud": 4.6300, "longitud": -74.0650}
LETICIA = {"latitud": -4.2153, "longitud": -69.9406}         # sin cobertura de ejemplo
COLOMBIA = timezone(timedelta(hours=-5))
CLAVE = "una clave larga de prueba"


def hace(horas):
    return (datetime.now(COLOMBIA) - timedelta(hours=horas)).isoformat(timespec="seconds")


def personal(admin, rol, empresa=None):
    correo = correo_nuevo(rol.lower())
    cuerpo = {"correo": correo, "clave": CLAVE, "rol": rol, **({"idEmpresa": empresa} if empresa else {})}
    estado, _, cuenta = http("POST", "/api/v1/cuentas", cuerpo, token=admin)
    assert estado == 201, (estado, cuenta)
    sesion = http("POST", "/api/v1/auth/login", {"correo": correo, "clave": CLAVE})[2]
    return sesion["tokenAcceso"], cuenta["idCuenta"]


def caso_de(token, id_reporte, extra=""):
    return buscar_caso(token, id_reporte, extra)


def reporte(token, servicio, ubicacion, magnitud):
    estado, _, r = http("POST", "/api/v1/reportes", {"tipoServicio": servicio, "ubicacion": ubicacion,
                                                     "inicioEstimado": hace(2), "magnitud": magnitud}, token=token)
    assert estado == 201, (estado, r)
    return r["idReporte"]


def estado_reporte(token, id_reporte):
    return http("GET", f"/api/v1/reportes/{id_reporte}", token=token)[2]["estado"]


print("0) Personal y ciudadanos")
admin = entrar_como_admin()["tokenAcceso"]
operador, id_operador = personal(admin, "OPERADOR", ENERGIA_ANDINA)
colega, id_colega = personal(admin, "OPERADOR", ENERGIA_ANDINA)
operador_otra, id_operador_otra = personal(admin, "OPERADOR", AGUAS_SABANA)
regulador, _ = personal(admin, "REGULADOR")
ciudadano = registrar_y_entrar()[0]["tokenAcceso"]
vecinos = [registrar_y_entrar()[0]["tokenAcceso"] for _ in range(3)]

print("1) Caso enrutado y priorizado (RF-6)")
id_reporte = reporte(ciudadano, "ENERGIA", BOGOTA, "SECTOR")
comprobar(esperar(lambda: caso_de(operador, id_reporte) is not None), "el operador de la empresa con cobertura lo ve")
caso = caso_de(operador, id_reporte)
comprobar(caso["idEmpresa"] == ENERGIA_ANDINA and caso["estado"] == "RECIBIDO", "enrutado a Energía Andina, RECIBIDO")
comprobar(caso["prioridad"] == "ALTA", f"prioridad ALTA por magnitud SECTOR (fue {caso['prioridad']})")
comprobar("idCiudadano" not in caso, "el caso no expone al ciudadano")
id_caso = caso["idCaso"]
comprobar(http("GET", f"/api/v1/casos/{id_caso}", token=operador_otra)[0] == 404, "otra empresa no lo ve (404)")

print("2) La validación comunitaria sube la prioridad (RF-4 -> RF-7)")
for v in vecinos:
    http("POST", f"/api/v1/reportes/{id_reporte}/validaciones",
         {"objeto": "CORTE", "sentido": "CONFIRMA", "ubicacion": CERCA}, token=v)
comprobar(esperar(lambda: http("GET", f"/api/v1/casos/{id_caso}", token=operador)[2].get("prioridad") == "CRITICA"),
          "con el corte validado pasa a CRITICA")

print("3) Atender, resolver y cerrar (RF-7, ADR-07)")
estado, _, c = http("POST", f"/api/v1/casos/{id_caso}/atender", token=operador)
comprobar(estado == 200 and c["estado"] == "EN_ATENCION" and c["asignadoA"] == id_operador,
          "EN_ATENCION y asignado a quien lo atiende")
comprobar(esperar(lambda: estado_reporte(ciudadano, id_reporte) == "EN_ATENCION"), "el ciudadano ve EN_ATENCION")
comprobar(http("POST", f"/api/v1/casos/{id_caso}/atender", token=operador)[0] == 409, "no se atiende dos veces (409)")
estado, _, c = http("POST", f"/api/v1/casos/{id_caso}/resolver", {"resolucion": "Se reemplazó el transformador"},
                    token=operador)
comprobar(estado == 200 and c["estado"] == "RESUELTO", "RESUELTO con su resolución")
comprobar(esperar(lambda: estado_reporte(ciudadano, id_reporte) == "RESTABLECIDO"), "el ciudadano ve RESTABLECIDO")
estado, _, r = http("POST", f"/api/v1/reportes/{id_reporte}/restablecimiento",
                    {"fechaRestablecimiento": hace(0.5)}, token=ciudadano)
comprobar(estado == 200 and r["duracionMinutos"] == 90, "el ciudadano aún registra la hora real (90 min)")
estado, _, c = http("POST", f"/api/v1/casos/{id_caso}/cerrar", {}, token=operador)
comprobar(estado == 200 and c["estado"] == "CERRADO", "CERRADO")
comprobar(esperar(lambda: estado_reporte(ciudadano, id_reporte) == "CERRADO"), "el ciudadano ve CERRADO")
historia = [e["estado"] for e in http("GET", f"/api/v1/reportes/{id_reporte}", token=ciudadano)[2]["historia"]]
comprobar(historia == ["RECIBIDO", "EN_ATENCION", "RESTABLECIDO", "RESTABLECIDO", "CERRADO"],
          f"historia del ciudadano: {historia}")

print("4) El restablecimiento del ciudadano resuelve el caso")
id_reporte_2 = reporte(ciudadano, "ENERGIA", BOGOTA, "CUADRA")
comprobar(esperar(lambda: caso_de(operador, id_reporte_2) is not None), "caso abierto")
http("POST", f"/api/v1/reportes/{id_reporte_2}/restablecimiento", {"fechaRestablecimiento": hace(1)}, token=ciudadano)
comprobar(esperar(lambda: (caso_de(operador, id_reporte_2) or {}).get("estado") == "RESUELTO"),
          "el caso pasa solo a RESUELTO")

print("5) Segregación de roles, enrutamiento y asignación")
comprobar(http("GET", f"/api/v1/casos/{id_caso}", token=regulador)[0] == 200, "el regulador consulta")
comprobar(http("POST", f"/api/v1/casos/{id_caso}/atender", token=regulador)[0] == 403, "el regulador no gestiona")
comprobar(http("GET", "/api/v1/casos", token=ciudadano)[0] == 403, "el ciudadano no ve casos")
id_reporte_3 = reporte(ciudadano, "ENERGIA", LETICIA, "VIVIENDA")
comprobar(esperar(lambda: caso_de(admin, id_reporte_3, "&sinEmpresa=true") is not None),
          "un reporte sin cobertura queda sin empresa (bandeja del administrador)")
id_caso_3 = caso_de(admin, id_reporte_3, "&sinEmpresa=true")["idCaso"]
comprobar(http("PUT", f"/api/v1/casos/{id_caso_3}/empresa", {"idEmpresa": ENERGIA_ANDINA}, token=operador)[0] == 404,
          "un operador no ve ni enruta casos sin empresa (404)")
estado, _, c = http("PUT", f"/api/v1/casos/{id_caso_3}/empresa", {"idEmpresa": ENERGIA_ANDINA}, token=admin)
comprobar(estado == 200 and c["idEmpresa"] == ENERGIA_ANDINA, "el administrador lo enruta")
estado, _, c = http("PUT", f"/api/v1/casos/{id_caso_3}/asignacion", {"idOperador": id_colega}, token=operador)
comprobar(estado == 200 and c["asignadoA"] == id_colega, "se asigna a un colega de la empresa")
estado, _, p = http("PUT", f"/api/v1/casos/{id_caso_3}/asignacion", {"idOperador": id_operador_otra}, token=operador)
comprobar(estado == 409 and p["motivo"] == "OPERADOR_INVALIDO", "no se asigna a un operador de otra empresa")
estado, _, c = http("PUT", f"/api/v1/casos/{id_caso_3}/prioridad", {"prioridad": "ALTA"}, token=operador)
comprobar(estado == 200 and c["prioridad"] == "ALTA" and c["prioridadManual"], "prioridad fijada a mano")

print("6) Moderación de denuncias anónimas (RF-5)")
denuncia = {"tipoServicio": "ENERGIA", "ubicacion": BOGOTA,
            "descripcion": "Conexión directa al poste de la esquina (prueba e2e fase 5)"}
cuerpo, tipo = multipart([("denuncia", None, "application/json", json.dumps(denuncia).encode()),
                          ("foto", "f.png", "image/png", png_con_metadatos())])
id_denuncia = json.loads(http_crudo("POST", "/api/v1/denuncias", cuerpo, tipo)[2])["idDenuncia"]


def moderacion():
    estado, _, m = http("GET", f"/api/v1/moderacion/denuncias/{id_denuncia}", token=operador)
    return m if estado == 200 else None


comprobar(esperar(lambda: moderacion() is not None), "la empresa con cobertura la recibe para moderar")
m = moderacion()
comprobar(m["estado"] == "PENDIENTE" and len(m["evidencias"]) == 1, "pendiente y con su foto")
comprobar(http("GET", f"/api/v1/moderacion/denuncias/{id_denuncia}", token=operador_otra)[0] == 404,
          "otra empresa no la ve")
estado, cabeceras, foto = http_crudo("GET", f"/api/v1/moderacion/denuncias/{id_denuncia}/evidencias/{m['evidencias'][0]}",
                                     None, None, operador)
comprobar(estado == 200 and b"GPSLatitude" not in foto, "el moderador ve la foto sin metadatos")
comprobar(http("POST", f"/api/v1/moderacion/denuncias/{id_denuncia}/decision",
               {"decision": "VALIDADA", "nota": "Revisión"}, token=regulador)[0] == 403, "el regulador no modera")
estado, _, m = http("POST", f"/api/v1/moderacion/denuncias/{id_denuncia}/decision",
                    {"decision": "VALIDADA", "nota": "Verificada por la cuadrilla en terreno"}, token=operador)
comprobar(estado == 200 and m["estado"] == "VALIDADA", "el operador la valida")
estado, _, p = http("POST", f"/api/v1/moderacion/denuncias/{id_denuncia}/decision",
                    {"decision": "DESCARTADA", "nota": "Otra opinión"}, token=colega)
comprobar(estado == 409 and p["motivo"] == "YA_MODERADA", "no se modera dos veces")

print("7) Colas de mensajes fallidos vacías")
for cola in ["atencion.eventos-reportes.dlq", "reportes.eventos-casos.dlq"]:
    comprobar(mensajes_en_cola(cola) == 0, f"{cola} vacía")

terminar()
