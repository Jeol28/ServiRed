#!/usr/bin/env python3
"""
Abuso con varias cuentas (RNF-SEG5, ADR-22) contra el compose:

  1. Una cuenta no puede inundar el sistema: crear reportes tiene cupo por cuenta (10 por
     hora, 5 por minuto); al pasarse recibe 429 con Retry-After, y otra cuenta sigue
     reportando aunque comparta la IP (compensación T4).
  2. Cuentas recién creadas no inflan una validación: sus votos se registran pero no suman
     (cuentaParaUmbral = false), y con 3 de ellas el corte NO queda validado.
  3. Tres ciudadanos establecidos (cuentas de más de 24 h) sí lo validan.
  4. La cuenta nueva ya votó: no puede repetir para sumar cuando envejezca.

Uso (desde la raíz):  python3 pruebas/e2e/abuso-por-cuenta.py
"""
import uuid
from datetime import datetime, timedelta, timezone

from comun import comprobar, http, registrar_y_entrar, terminar

COLOMBIA = timezone(timedelta(hours=-5))
LUGAR = {"latitud": 4.6097, "longitud": -74.0817}
CERCA = {"latitud": 4.6100, "longitud": -74.0820}


def reporte(token, reintentar_429=True):
    inicio = (datetime.now(COLOMBIA) - timedelta(hours=1)).isoformat(timespec="seconds")
    return http("POST", "/api/v1/reportes", {"tipoServicio": "ENERGIA", "ubicacion": LUGAR, "inicioEstimado": inicio,
                                             "magnitud": "CUADRA"},
                correlacion=str(uuid.uuid4()), token=token, reintentar_429=reintentar_429)


def votar(token, id_reporte):
    return http("POST", f"/api/v1/reportes/{id_reporte}/validaciones",
                {"objeto": "CORTE", "sentido": "CONFIRMA", "ubicacion": CERCA}, token=token, reintentar_429=False)


print("1. Una cuenta no puede inundar con reportes")
inundadora = registrar_y_entrar()[0]["tokenAcceso"]
estados = [reporte(inundadora, reintentar_429=False) for _ in range(6)]
codigos = [e for e, _, _ in estados]
comprobar(codigos[:5] == [201] * 5, f"los 5 primeros reportes del minuto se aceptan ({codigos[:5]})")
estado, cabeceras, cuerpo = estados[5]
comprobar(estado == 429 and cabeceras.get("Retry-After"), f"el 6.º recibe 429 con Retry-After ({estado})")
comprobar((cuerpo or {}).get("status") == 429, "como problem+json")
otra = registrar_y_entrar()[0]["tokenAcceso"]
comprobar(reporte(otra, reintentar_429=False)[0] == 201, "otra cuenta sigue reportando")

print("2. Cuentas nuevas no inflan la validación")
autor = registrar_y_entrar()[0]["tokenAcceso"]
estado, _, r = reporte(autor)
id_reporte = r["idReporte"]
nuevas = [registrar_y_entrar(nueva=True)[0]["tokenAcceso"] for _ in range(3)]
votos = [votar(t, id_reporte) for t in nuevas]
comprobar([e for e, _, _ in votos] == [201, 201, 201], "los 3 votos de cuentas nuevas se registran (201)")
comprobar(all(v["cuentaParaUmbral"] is False for _, _, v in votos), "pero ninguno cuenta para el umbral")
comprobar(votos[-1][2]["confirmacionesNetas"] == 0 and votos[-1][2]["validado"] is False,
          f"el corte NO queda validado ({votos[-1][2]['confirmacionesNetas']} netas)")

print("3. Ciudadanos establecidos sí validan")
establecidos = [registrar_y_entrar()[0]["tokenAcceso"] for _ in range(3)]
votos = [votar(t, id_reporte) for t in establecidos]
comprobar(all(v["cuentaParaUmbral"] for _, _, v in votos), "sus votos cuentan")
comprobar(votos[-1][2]["confirmacionesNetas"] == 3 and votos[-1][2]["validado"] is True,
          "con 3 confirmaciones de cuentas establecidas el corte queda validado")

print("4. La cuenta nueva no puede volver a votar")
estado, _, v = votar(nuevas[0], id_reporte)
comprobar(estado == 200 and v["confirmacionesNetas"] == 3, "repetir el voto no cambia nada (200)")

terminar()
