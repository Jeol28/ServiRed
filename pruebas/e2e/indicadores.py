#!/usr/bin/env python3
"""
Prueba de punta a punta de la fase 6 (RF-8 a RF-10, RNF-DAT2, RNF-DAT4) contra el
entorno de Docker Compose, a través del gateway. Usa un municipio sin datos en
cada corrida para poder comprobar la supresión exactamente:

  1. Con 2 reportes el municipio se suprime (k-anonimato, umbral 3).
  2. Con 4 reportes (3 restablecidos) se publican frecuencia, duración y severidad.
  3. El mapa público muestra celdas de ~1,1 km sin identificadores.
  4. Los datos abiertos (CSV) traen el municipio, sin coordenadas ni ids.
  5. Metadatos: definiciones, fuente, periodo, cobertura y actualización.
  6. Exportación regulatoria: solo regulador/administrador, coordenadas a ~110 m,
     y la caso.* de Atención se refleja en la vista.
  7. Mapa operativo: personal; el operador solo ve su empresa.
  8. Ningún texto del ciudadano sale en lo público ni en la exportación; DLQ vacía.

Uso (desde la raíz, con el compose arriba):  python3 pruebas/e2e/indicadores.py
"""
import uuid
from datetime import datetime, timedelta, timezone

from comun import (comprobar, correo_nuevo, entrar_como_admin, esperar, http, http_crudo, mensajes_en_cola,
                   registrar_y_entrar, sql, terminar)

COLOMBIA = timezone(timedelta(hours=-5))
MARCA = f"marca-privada-{uuid.uuid4().hex[:8]}"
CLAVE = "una clave larga de prueba"

codigo, lat, lon = sql("""
    SELECT codigo || '|' || latitud || '|' || longitud FROM catalogo.municipios
    WHERE tipo = 'MUNICIPIO' AND codigo NOT IN (SELECT codigo_municipio FROM indicadores.vista_reportes
                                                WHERE codigo_municipio IS NOT NULL)
      AND codigo NOT IN (SELECT codigo_municipio FROM catalogo.coberturas)
    ORDER BY random() LIMIT 1""").split("|")
lat, lon = float(lat), float(lon)
print(f"Municipio de la corrida: {codigo}")


def hace(horas):
    return (datetime.now(COLOMBIA) - timedelta(hours=horas)).isoformat(timespec="seconds")


def reportar(token, desplazamiento, restablecer_min=None):
    estado, _, r = http("POST", "/api/v1/reportes", {
        "tipoServicio": "ACUEDUCTO", "ubicacion": {"latitud": lat + desplazamiento, "longitud": lon},
        "inicioEstimado": hace(5), "magnitud": "BARRIO", "descripcion": f"Sin agua {MARCA}"}, token=token)
    assert estado == 201 and r["codigoMunicipio"] == codigo, (estado, r)
    if restablecer_min:
        inicio = datetime.fromisoformat(r["inicioEstimado"].replace("Z", "+00:00"))
        fin = (inicio + timedelta(minutes=restablecer_min)).isoformat()
        assert http("POST", f"/api/v1/reportes/{r['idReporte']}/restablecimiento",
                    {"fechaRestablecimiento": fin}, token=token)[0] == 200
    return r["idReporte"]


def en_vista(n):
    return sql(f"SELECT count(*) FROM indicadores.vista_reportes WHERE codigo_municipio = '{codigo}' "
               f"AND fecha_registro IS NOT NULL") == str(n)


def resumen():
    return http("GET", f"/api/v1/indicadores/resumen?codigoMunicipio={codigo}&tipoServicio=ACUEDUCTO")[2]


ciudadano = registrar_y_entrar()[0]["tokenAcceso"]
admin = entrar_como_admin()["tokenAcceso"]

print("1) Supresión de grupos pequeños (RNF-DAT2)")
ids = [reportar(ciudadano, 0.0001, 60), reportar(ciudadano, 0.0002)]
comprobar(esperar(lambda: en_vista(2)), "los 2 reportes llegan a la vista de lectura")
r = resumen()
comprobar(r["indicadores"]["suprimido"] is True and "reportes" not in r["indicadores"],
          "con 2 reportes el municipio se publica suprimido, sin conteo")

print("2) Indicadores publicados (RF-9)")
ids += [reportar(ciudadano, 0.0003, 120), reportar(ciudadano, 0.0004, 180)]
comprobar(esperar(lambda: en_vista(4) and resumen()["indicadores"].get("reportes") == 4), "frecuencia: 4 reportes")
i = resumen()["indicadores"]
comprobar(i["duracion"]["mediana"] == 120 and i["duracion"]["reportesConDuracion"] == 3,
          f"duración mediana de 120 min con 3 restablecidos ({i['duracion']})")
comprobar(i["severidad"]["indice"] == 3 and i["severidad"]["porMagnitud"]["BARRIO"] == 4, "severidad: índice 3 (BARRIO)")
comprobar(i["atencion"]["porcentajeResueltos"] == 75, "75 % resueltos")
estado, _, t = http("GET", f"/api/v1/indicadores/territorial?nivel=MUNICIPIO&tipoServicio=ACUEDUCTO")
fila = next((x for x in t["territorios"] if x["codigo"] == codigo), None) if estado == 200 else None
comprobar(fila is not None and fila["indicadores"]["reportes"] == 4, "aparece en la distribución territorial")

print("3) Mapa público por celdas (RF-8)")


def celdas_cercanas():
    estado, _, m = http("GET", "/api/v1/mapas/publico?tipoServicio=ACUEDUCTO")
    return estado, m, [c for c in m["celdas"] if abs(c["latitud"] - lat) < 0.02 and abs(c["longitud"] - lon) < 0.02]


esperar(lambda: sum(c["reportes"] for c in celdas_cercanas()[2]) >= 4, 30)
estado, mapa, cerca = celdas_cercanas()
comprobar(estado == 200 and sum(c["reportes"] for c in cerca) >= 4, "las celdas cercanas suman los 4 reportes")
# Solo el centro, el conteo y el lado de la celda (ADR-28): nada que identifique reportes.
comprobar(all(set(c) == {"latitud", "longitud", "reportes", "celdaGrados"} for c in mapa["celdas"]),
          "las celdas no traen ids")
comprobar(mapa["celdaGrados"] == 0.01, "celdas de 0,01° (~1,1 km)")

print("4) Datos abiertos (RF-10)")
estado, cabeceras, csv = http_crudo("GET", "/api/v1/datos-abiertos/indicadores-municipales?formato=csv&tipoServicio=ACUEDUCTO",
                                    None, None)
texto = csv.decode()
encabezado = texto.splitlines()[0]
comprobar(estado == 200 and cabeceras.get("Content-Type", "").startswith("text/csv"), "CSV descargable")
comprobar(any(l.startswith(codigo + ",") and ",ACUEDUCTO," in l for l in texto.splitlines()), "incluye el municipio")
comprobar(not {"latitud", "longitud", "id_reporte"} & set(encabezado.split(",")), "sin coordenadas ni identificadores")

print("5) Metadatos (RNF-DAT4)")
m = resumen()["metadatos"]
comprobar(len(m["indicadores"]) == 5 and all(d["definicion"] and d["fuente"] for d in m["indicadores"]),
          "cada indicador trae definición y fuente")
comprobar(m["periodo"]["desde"] and m["periodo"]["hasta"] and m["actualizadoEn"] and m["cobertura"]["reportesEnVista"] >= 4,
          "periodo, cobertura y fecha de actualización")

print("6) Exportación regulatoria (RF-10)")
correo_reg = correo_nuevo("regulador")
http("POST", "/api/v1/cuentas", {"correo": correo_reg, "clave": CLAVE, "rol": "REGULADOR"}, token=admin)
regulador = http("POST", "/api/v1/auth/login", {"correo": correo_reg, "clave": CLAVE})[2]["tokenAcceso"]
correo_op = correo_nuevo("operador")
http("POST", "/api/v1/cuentas", {"correo": correo_op, "clave": CLAVE, "rol": "OPERADOR",
                                 "idEmpresa": "a0000000-0000-4000-8000-000000000001"}, token=admin)
operador = http("POST", "/api/v1/auth/login", {"correo": correo_op, "clave": CLAVE})[2]["tokenAcceso"]
ruta = f"/api/v1/indicadores/exportaciones/reportes?formato=csv&codigoMunicipio={codigo}"
comprobar(http_crudo("GET", ruta, None, None)[0] == 401, "sin token -> 401")
comprobar(http_crudo("GET", ruta, None, None, operador)[0] == 403, "operador -> 403")


def exportacion():
    return http_crudo("GET", ruta, None, None, regulador)[2].decode()


comprobar(esperar(lambda: exportacion().count("\n") == 5), "el regulador exporta los 4 reportes")
filas = exportacion().strip().splitlines()
columnas = filas[0].split(",")
comprobar(all(len(f.split(",")[columnas.index("latitud")].split(".")[1]) <= 3 for f in filas[1:]),
          "coordenadas redondeadas a 3 decimales")
comprobar(esperar(lambda: exportacion().count(",RESUELTO,") >= 3),
          "la vista refleja que Atención resolvió los casos restablecidos (caso.actualizado)")

print("7) Mapa operativo (RF-8)")
estado, _, puntos = http("GET", f"/api/v1/mapas/operativo?codigoMunicipio={codigo}", token=regulador)
comprobar(estado == 200 and len(puntos) == 4, "el regulador ve los 4 puntos")
estado, _, puntos = http("GET", f"/api/v1/mapas/operativo?codigoMunicipio={codigo}", token=operador)
comprobar(estado == 200 and puntos == [], "el operador no ve reportes fuera de su empresa")
comprobar(http("GET", "/api/v1/mapas/operativo", token=ciudadano)[0] == 403, "el ciudadano no accede")

print("8) Nada privado sale; sin mensajes fallidos")
publico = str(resumen()) + str(mapa) + texto + str(t)
comprobar(MARCA not in publico and MARCA not in exportacion(), "ninguna descripción del ciudadano se publica ni exporta")
comprobar(not any(i in publico for i in ids), "lo público no trae ids de reporte")
comprobar(mensajes_en_cola("indicadores.eventos.dlq") == 0, "indicadores.eventos.dlq vacía")

terminar()
