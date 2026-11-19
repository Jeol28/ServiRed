#!/usr/bin/env python3
"""
Privacidad rural del mapa público (ADR-28).

En las cabeceras municipales el mapa usa celdas de 0,01° (~1,1 km) y las muestra desde 3
reportes. Fuera de ellas, donde una celda de 1 km puede tener solo unas pocas viviendas, usa
celdas de 0,05° (~5,5 km) y las muestra desde 5. Comprueba contra el compose:

  1. Rural (Sumapaz, Bogotá): con 4 reportes la celda no aparece; con 5 aparece como una
     celda de 0,05° centrada en la cuadrícula gruesa, nunca como celdas pequeñas.
  2. Cabecera (Bogotá urbana): con 3 reportes aparece una celda de 0,01°.
  3. El catálogo publica la política y la vista queda alineada con el origen (R14).

Elige celdas vacías para el servicio usado (consultando la vista), así que no depende de
los datos que ya haya. Uso (desde la raíz):  python3 pruebas/e2e/privacidad-rural.py
"""
import math
import random
import subprocess
import sys
import uuid
from datetime import datetime, timedelta, timezone

from comun import RAIZ, comprobar, esperar, http, registrar_y_entrar, sql, terminar

INICIO = (datetime.now(timezone(timedelta(hours=-5))) - timedelta(hours=2)).isoformat(timespec="seconds")


def centro(valor, lado):
    return round((math.floor(valor / lado) + 0.5) * lado, 4)


def filas_en_celda(lat, lon, lado, servicio):
    """Reportes de ese servicio en la vista dentro de la celda (centro lat, lon)."""
    m = lado / 2
    return int(sql(f"SELECT count(*) FROM indicadores.vista_reportes WHERE tipo_servicio = '{servicio}' "
                   f"AND latitud >= {lat - m} AND latitud < {lat + m} AND longitud >= {lon - m} AND longitud < {lon + m}"))


def celda_vacia(lat_rango, lon_rango, lado, servicio):
    for _ in range(200):
        lat = centro(random.uniform(*lat_rango), lado)
        lon = centro(random.uniform(*lon_rango), lado)
        if filas_en_celda(lat, lon, lado, servicio) == 0:
            return lat, lon
    raise SystemExit("No se encontró una celda vacía")


def reportar(token, servicio, lat, lon):
    estado, _, r = http("POST", "/api/v1/reportes", {"tipoServicio": servicio, "ubicacion": {
        "latitud": round(lat, 6), "longitud": round(lon, 6)}, "inicioEstimado": INICIO, "magnitud": "VIVIENDA"},
        correlacion=str(uuid.uuid4()), token=token)
    return estado, r


def celdas(servicio):
    estado, _, m = http("GET", f"/api/v1/mapas/publico?tipoServicio={servicio}&codigoMunicipio=11001")
    return m["celdas"] if estado == 200 else None


def buscar(lista, lat, lon):
    return next((c for c in lista or [] if abs(c["latitud"] - lat) < 1e-6 and abs(c["longitud"] - lon) < 1e-6), None)


ciudadanos = [registrar_y_entrar()[0]["tokenAcceso"] for _ in range(3)]

print("1. Rural: celdas de 0,05° desde 5 reportes")
SERVICIO_RURAL = "ALCANTARILLADO"
def en_bogota_rural(puntos):
    """Los cinco puntos caen en Bogotá y fuera de su cabecera, según los polígonos de la base."""
    valores = ", ".join(f"({la}, {lo})" for la, lo in puntos)
    return sql("SELECT bool_and(catalogo.municipio_en(la, lo) = '11001' AND NOT catalogo.en_cabecera(la, lo)) "
               f"FROM (VALUES {valores}) p(la, lo)") == "t"


for _ in range(50):
    lat_c, lon_c = celda_vacia((3.95, 4.10), (-74.30, -74.15), 0.05, SERVICIO_RURAL)   # Sumapaz (Bogotá rural)
    puntos = [(round(lat_c + random.uniform(-0.02, 0.02), 6), round(lon_c + random.uniform(-0.02, 0.02), 6))
              for _ in range(5)]
    if en_bogota_rural(puntos):
        break
else:
    raise SystemExit("No se encontró una celda rural de Bogotá")
for i, (la, lo) in enumerate(puntos[:4]):
    estado, r = reportar(ciudadanos[i // 2], SERVICIO_RURAL, la, lo)
    comprobar(estado == 201 and r["codigoMunicipio"] == "11001", f"reporte rural {i + 1} en Bogotá ({estado})")
comprobar(esperar(lambda: filas_en_celda(lat_c, lon_c, 0.05, SERVICIO_RURAL) == 4, 30), "los 4 llegaron a la vista")
comprobar(sql(f"SELECT bool_or(en_cabecera) FROM indicadores.vista_reportes WHERE tipo_servicio = '{SERVICIO_RURAL}' "
              f"AND abs(latitud - {lat_c}) < 0.025 AND abs(longitud - {lon_c}) < 0.025") == "f",
          "la vista los clasifica fuera de la cabecera")
mapa = celdas(SERVICIO_RURAL)
cerca = [c for c in mapa if abs(c["latitud"] - lat_c) < 0.03 and abs(c["longitud"] - lon_c) < 0.03]
comprobar(not cerca, f"con 4 reportes la zona no aparece en el mapa público ({cerca})")

estado, _ = reportar(ciudadanos[2], SERVICIO_RURAL, *puntos[4])
comprobar(estado == 201, "quinto reporte rural")
comprobar(esperar(lambda: buscar(celdas(SERVICIO_RURAL), lat_c, lon_c) is not None, 30),
          "con 5 aparece la celda rural")
celda = buscar(celdas(SERVICIO_RURAL), lat_c, lon_c) or {}
comprobar(celda.get("reportes") == 5 and celda.get("celdaGrados") == 0.05,
          f"una sola celda de 0,05° con 5 reportes ({celda})")
pequenas = [c for c in celdas(SERVICIO_RURAL) if abs(c["latitud"] - lat_c) < 0.03 and abs(c["longitud"] - lon_c) < 0.03
            and c.get("celdaGrados") == 0.01]
comprobar(not pequenas, "ninguna celda pequeña delata la ubicación")

print("2. Cabecera: celdas de 0,01° desde 3 reportes")
SERVICIO_URBANO = "ASEO"
lat_u, lon_u = celda_vacia((4.60, 4.68), (-74.10, -74.06), 0.01, SERVICIO_URBANO)   # Bogotá urbana
for i in range(3):
    estado, _ = reportar(ciudadanos[i], SERVICIO_URBANO, lat_u + random.uniform(-0.004, 0.004),
                         lon_u + random.uniform(-0.004, 0.004))
    comprobar(estado == 201, f"reporte urbano {i + 1}")
comprobar(esperar(lambda: buscar(celdas(SERVICIO_URBANO), lat_u, lon_u) is not None, 30), "aparece la celda urbana")
celda = buscar(celdas(SERVICIO_URBANO), lat_u, lon_u) or {}
comprobar(celda.get("reportes") == 3 and celda.get("celdaGrados") == 0.01,
          f"celda de 0,01° con 3 reportes ({celda})")

print("3. Política publicada y vista alineada")
_, _, catalogo = http("GET", "/api/v1/indicadores/catalogo")
comprobar(catalogo.get("umbralSupresionRural") == 5 and catalogo.get("celdaMapaRuralGrados") == 0.05,
          "el catálogo publica k y celda rurales")
verificacion = None


def alineada():
    """La proyección de los últimos eventos (casos abiertos) puede tardar unos segundos."""
    global verificacion
    verificacion = subprocess.run([sys.executable, "infra/operacion/reconstruir-indicadores.py"], cwd=RAIZ,
                                  capture_output=True, text=True)
    return verificacion.returncode == 0


comprobar(esperar(alineada, 30), "la vista (con en_cabecera) está alineada con el origen: "
          + (verificacion.stdout.strip().splitlines() or ["?"])[-1])

terminar()
