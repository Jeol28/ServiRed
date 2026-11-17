#!/usr/bin/env python3
"""
Prueba de punta a punta de la fase 4 (RF-1, RF-3, RF-4, RF-5, RNF-SEG4,
RNF-SEG5) contra el entorno de Docker Compose, siempre a través del gateway:

  1. Fotografía del reporte: se guarda en el almacén S3 sin EXIF/GPS; solo el
     autor la ve; archivos que no son imágenes -> 422; máximo 3 -> 409.
  2. Validación comunitaria: vecinos a <= 1 km ven el evento sin datos del autor;
     con 3 confirmaciones netas queda validado; votar dos veces es idempotente;
     el autor no vota y alguien lejano tampoco.
  3. Restablecimiento: duración calculada, una sola vez; luego se valida.
  4. Los eventos restablecimiento.registrado y validacion.registrada se publican
     y Atención los consume sin enviarlos a la DLQ.
  5. Denuncia anónima con foto: aunque el cliente envíe su token y su
     X-Correlation-Id, nada lo vincula con la denuncia.
  6. Límite de abuso en denuncias (429).

Uso (desde la raíz, con el compose arriba):  python3 pruebas/e2e/reportes-completo.py
"""
import json
import uuid
from datetime import datetime, timedelta, timezone

from comun import (comprobar, compose, esperar, http, http_crudo, mensajes_en_cola, multipart, png_con_metadatos,
                   registrar_y_entrar, sql, terminar)

BUCARAMANGA = {"latitud": 7.1193, "longitud": -73.1227}
CERCA = {"latitud": 7.1210, "longitud": -73.1230}      # ~190 m
LEJOS = {"latitud": 7.1400, "longitud": -73.1227}      # ~2,3 km
COLOMBIA = timezone(timedelta(hours=-5))

print("0) Ciudadanos")
autor, _ = registrar_y_entrar()
vecinos = [registrar_y_entrar()[0] for _ in range(4)]
t_autor = autor["tokenAcceso"]
inicio = (datetime.now(COLOMBIA) - timedelta(hours=3)).isoformat(timespec="seconds")
estado, _, reporte = http("POST", "/api/v1/reportes", {
    "tipoServicio": "ACUEDUCTO", "ubicacion": BUCARAMANGA, "inicioEstimado": inicio, "magnitud": "BARRIO",
    "descripcion": "Sin agua en el barrio (prueba e2e)"}, token=t_autor)
comprobar(estado == 201 and reporte["codigoMunicipio"] == "68001", "reporte creado en Bucaramanga (68001)")
id_reporte = reporte["idReporte"]

print("1) Fotografía del reporte (RF-1)")
original = png_con_metadatos()
cuerpo, tipo = multipart([("foto", "foto.png", "image/png", original)])
estado, _, r = http_crudo("POST", f"/api/v1/reportes/{id_reporte}/evidencias", cuerpo, tipo, t_autor)
r = json.loads(r)
comprobar(estado == 201 and len(r["evidencias"]) == 1, "201 y la evidencia queda en el reporte")
url = r["evidencias"][0]["url"]
estado, cabeceras, guardada = http_crudo("GET", url, None, None, t_autor)
comprobar(estado == 200 and guardada.startswith(b"\x89PNG"), "el autor descarga la imagen")
comprobar(b"GPSLatitude" not in guardada and b"TelefonoDelDenunciante" not in guardada,
          "la imagen guardada no tiene EXIF, GPS ni modelo del teléfono")
comprobar(cabeceras.get("X-Content-Type-Options") == "nosniff", "se sirve con nosniff")
comprobar(http_crudo("GET", url, None, None, vecinos[0]["tokenAcceso"])[0] == 404, "otro ciudadano no la ve")
cuerpo, tipo = multipart([("foto", "foto.png", "image/png", b"<?php system($_GET['c']); ?>")])
comprobar(http_crudo("POST", f"/api/v1/reportes/{id_reporte}/evidencias", cuerpo, tipo, t_autor)[0] == 422,
          "un archivo que no es imagen -> 422")
for _ in range(2):
    c, t = multipart([("foto", "f.png", "image/png", original)])
    http_crudo("POST", f"/api/v1/reportes/{id_reporte}/evidencias", c, t, t_autor)
c, t = multipart([("foto", "f.png", "image/png", original)])
comprobar(http_crudo("POST", f"/api/v1/reportes/{id_reporte}/evidencias", c, t, t_autor)[0] == 409,
          "la cuarta foto -> 409")
comprobar(sql(f"SELECT count(*) FROM reportes.evidencias WHERE id_reporte = '{id_reporte}'") == "3",
          "3 referencias en la base de datos")

print("2) Validación comunitaria (RF-4)")
q = f"latitud={CERCA['latitud']}&longitud={CERCA['longitud']}"
estado, _, cercanos = http("GET", f"/api/v1/reportes/cercanos?{q}", token=vecinos[0]["tokenAcceso"])
propio = [x for x in cercanos if x["idReporte"] == id_reporte] if estado == 200 else []
comprobar(bool(propio) and "descripcion" not in propio[0], "el vecino ve el evento cercano sin la descripción")
voto = {"objeto": "CORTE", "sentido": "CONFIRMA", "ubicacion": CERCA}
resultados = [http("POST", f"/api/v1/reportes/{id_reporte}/validaciones", voto, token=v["tokenAcceso"])
              for v in vecinos[:3]]
comprobar([e for e, _, _ in resultados] == [201, 201, 201], "tres vecinos confirman")
comprobar([r["validado"] for _, _, r in resultados] == [False, False, True], "queda validado con 3 netas")
estado, _, repetido = http("POST", f"/api/v1/reportes/{id_reporte}/validaciones",
                           {**voto, "sentido": "CONTROVIERTE"}, token=vecinos[0]["tokenAcceso"])
comprobar(estado == 200 and repetido["confirmacionesNetas"] == 3, "votar de nuevo no cambia nada (idempotente)")
estado, _, p = http("POST", f"/api/v1/reportes/{id_reporte}/validaciones", voto, token=t_autor)
comprobar(estado == 409 and p["motivo"] == "AUTOR", "el autor no valida su reporte")
estado, _, p = http("POST", f"/api/v1/reportes/{id_reporte}/validaciones", {**voto, "ubicacion": LEJOS},
                    token=vecinos[3]["tokenAcceso"])
comprobar(estado == 409 and p["motivo"] == "LEJOS", "alguien a más de 1 km no valida")

print("3) Restablecimiento (RF-3)")
fin = (datetime.now(COLOMBIA) - timedelta(minutes=30)).isoformat(timespec="seconds")
estado, _, r = http("POST", f"/api/v1/reportes/{id_reporte}/restablecimiento",
                    {"fechaRestablecimiento": fin, "comentario": "Volvió el agua"}, token=t_autor)
comprobar(estado == 200 and r["estado"] == "RESTABLECIDO", "queda RESTABLECIDO")
comprobar(r["duracionMinutos"] == 150, f"duración de 150 minutos (fue {r.get('duracionMinutos')})")
comprobar(r["historia"][-1]["estado"] == "RESTABLECIDO", "la historia registra el restablecimiento")
estado, _, p = http("POST", f"/api/v1/reportes/{id_reporte}/restablecimiento", {"fechaRestablecimiento": fin},
                    token=t_autor)
comprobar(estado == 409, "no se restablece dos veces")
estado, _, r = http("POST", f"/api/v1/reportes/{id_reporte}/validaciones",
                    {"objeto": "RESTABLECIMIENTO", "sentido": "CONFIRMA", "ubicacion": CERCA},
                    token=vecinos[1]["tokenAcceso"])
comprobar(estado == 201 and r["objeto"] == "RESTABLECIMIENTO", "un vecino confirma el restablecimiento")
estado, _, mios = http("GET", "/api/v1/reportes", token=t_autor)
comprobar(estado == 200 and mios["elementos"][0]["validacion"]["corte"]["confirmacionesNetas"] == 3,
          "el autor ve el conteo en sus reportes")

print("4) Eventos publicados y consumidos")
tipos = sql(f"SELECT string_agg(DISTINCT tipo, ',' ORDER BY tipo) FROM infraestructura.outbox "
            f"WHERE sobre->'datos'->>'idReporte' = '{id_reporte}'")
propios = {"reporte.creado", "restablecimiento.registrado", "validacion.registrada"}
comprobar(propios <= set(tipos.split(",")), f"eventos de Reportes en el outbox: {tipos}")
# Desde la fase 5, Atención reacciona (validación y restablecimiento) con caso.actualizado.
comprobar(set(tipos.split(",")) - propios <= {"caso.actualizado"}, "Atención solo agrega caso.actualizado")
comprobar(esperar(lambda: sql(f"SELECT count(*) FROM infraestructura.outbox WHERE publicado_en IS NULL "
                              f"AND sobre->'datos'->>'idReporte' = '{id_reporte}'") == "0"), "todos publicados")
comprobar(mensajes_en_cola("atencion.eventos-reportes.dlq") == 0, "Atención no envió nada a la DLQ")

print("5) Denuncia anónima (RF-5, RNF-SEG4)")
correlacion_cliente = str(uuid.uuid4())
denuncia = {"tipoServicio": "ENERGIA", "ubicacion": BUCARAMANGA,
            "descripcion": "Conexión directa al transformador de la esquina (prueba e2e)"}
cuerpo, tipo = multipart([("denuncia", None, "application/json", json.dumps(denuncia).encode()),
                          ("foto", "f.png", "image/png", png_con_metadatos())])
estado, cabeceras, r = http_crudo("POST", "/api/v1/denuncias", cuerpo, tipo, t_autor,
                                  {"X-Correlation-Id": correlacion_cliente})
r = json.loads(r)
comprobar(estado == 202 and r["estado"] == "PENDIENTE_VALIDACION" and r["tieneEvidencia"], "202 con foto")
id_denuncia = r["idDenuncia"]
comprobar(cabeceras.get("X-Correlation-Id") != correlacion_cliente, "el gateway ignora el X-Correlation-Id del cliente")
id_autor = autor["cuenta"]["idCuenta"]
fila = sql(f"SELECT row_to_json(d)::text FROM reportes.denuncias d WHERE id = '{id_denuncia}'")
sobre = sql(f"SELECT sobre::text FROM infraestructura.outbox WHERE tipo = 'denuncia.recibida' "
            f"AND sobre->'datos'->>'idDenuncia' = '{id_denuncia}'")
comprobar(bool(fila) and id_autor not in fila, "la denuncia guardada no contiene la cuenta del denunciante")
comprobar(bool(sobre) and id_autor not in sobre and correlacion_cliente not in sobre,
          "denuncia.recibida no contiene la cuenta ni la correlación del cliente")
logs = compose("logs", "--no-color", "gateway", "reportes", "persistencia").stdout
lineas = [linea for linea in logs.splitlines() if id_denuncia in linea]
comprobar(bool(lineas) and not any(id_autor in linea for linea in lineas), "los logs de la denuncia no la vinculan")
columnas = sql("SELECT string_agg(column_name, ',') FROM information_schema.columns "
               "WHERE table_schema = 'reportes' AND table_name = 'denuncias'")
prohibidas = {"ip", "id_ciudadano", "id_cuenta", "correo", "dispositivo", "user_agent"}
comprobar(not prohibidas & set(columnas.split(",")), f"la tabla no tiene campos de identidad ({columnas})")

print("6) Límite de abuso en denuncias (RNF-SEG5)")
# La ventana es fija (5 por minuto): con 11 intentos alguna ventana recibe 6 aunque se cruce un minuto.
estados = [http("POST", "/api/v1/denuncias", denuncia, reintentar_429=False)[0] for _ in range(11)]
comprobar(429 in estados, f"429 al superar el límite ({estados})")

terminar()
