#!/usr/bin/env python3
"""
Prueba de punta a punta de la fase 8: los portales web (nginx + Angular) contra
el entorno de Docker Compose.

  1. Cada portal sirve la SPA con cabeceras de seguridad (CSP sin scripts en
     línea, sin marcos, sin referrer) y responde index.html en sus rutas.
  2. El proxy /api/ llega al gateway: consultas públicas sin token en el portal
     público e ingreso y bandeja de casos del personal en el de operación.
  3. Denuncia anónima por la API (como las apps): se acepta sin credenciales, nginx no la
     registra y ningún registro del portal contiene IP de clientes.
  4. El límite de abuso es por cliente detrás del proxy, y X-Forwarded-For
     enviado directamente al gateway no permite evadirlo.

Uso (desde la raíz, con el compose arriba):  python3 pruebas/e2e/portales-web.py
"""
import json
import re
import time
import urllib.error
import urllib.request

from comun import ENV, comprobar, compose, terminar

PUBLICO = "http://127.0.0.1:4300"
OPERADOR = "http://127.0.0.1:4200"
GATEWAY = "http://127.0.0.1:8080"


def pedir(base, ruta, metodo="GET", cuerpo=None, cabeceras=None):
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    h = {"content-type": "application/json", **(cabeceras or {})} if datos else dict(cabeceras or {})
    req = urllib.request.Request(base + ruta, data=datos, method=metodo, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.headers, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read().decode("utf-8", "replace")


def denuncia(texto):
    return {"tipoServicio": "ENERGIA", "ubicacion": {"latitud": 4.6097, "longitud": -74.0817}, "descripcion": texto}


print("1) SPA y cabeceras de seguridad")
for nombre, base, ruta in [("público", PUBLICO, "/indicadores"), ("operación", OPERADOR, "/casos/123")]:
    estado, h, html = pedir(base, "/")
    csp = h.get("Content-Security-Policy", "")
    comprobar(estado == 200 and "<app-root" in html, f"portal {nombre}: sirve la SPA")
    comprobar("script-src 'self'" in csp and "frame-ancestors 'none'" in csp, f"portal {nombre}: CSP estricta")
    comprobar(h.get("X-Frame-Options") == "DENY" and h.get("Referrer-Policy") == "no-referrer",
              f"portal {nombre}: sin marcos ni referrer")
    comprobar(not re.search(r"\son[a-z]+=", html) and "<script>" not in html, f"portal {nombre}: sin scripts en línea")
    comprobar(h.get("Server", "").lower() == "nginx", f"portal {nombre}: sin versión del servidor")
    estado, _, html = pedir(base, ruta)
    comprobar(estado == 200 and "<app-root" in html, f"portal {nombre}: {ruta} devuelve la SPA")
comprobar(all("geolocation=()" in pedir(b, "/")[1].get("Permissions-Policy", "") for b in (PUBLICO, OPERADOR)),
          "ningún portal pide la ubicación (reportes y denuncias solo en las apps)")

print("1b) Una sola dirección: el portal de operación en /operacion/ del público")
estado, _, html = pedir(PUBLICO, "/operacion/casos/123")
comprobar(estado == 200 and "Portal de operación" in html and '<base href="/operacion/"' in html,
          "/operacion/casos/123 abre el portal de operación")
js = re.search(r"main-[A-Za-z0-9]+\.js", html).group(0)
estado, h, _ = pedir(PUBLICO, f"/operacion/{js}")
comprobar(estado == 200 and "javascript" in h.get("Content-Type", ""), "sus archivos llegan con el tipo correcto")
comprobar(pedir(PUBLICO, "/operacion/favicon.svg")[1].get("Content-Type") == "image/svg+xml", "y su ícono también")
comprobar(len(pedir(PUBLICO, "/operacion/")[1].get_all("Content-Security-Policy") or []) == 1, "sin cabeceras duplicadas")
req = urllib.request.Request(PUBLICO + "/operacion", method="GET")
class SinSeguir(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k): return None
try:
    urllib.request.build_opener(SinSeguir).open(req, timeout=10)
    redireccion = None
except urllib.error.HTTPError as e:
    redireccion = e.headers.get("Location")
comprobar(redireccion == "/operacion/", f"/operacion redirige de forma relativa ({redireccion})")

print("2) Proxy hacia el gateway")
estado, h, cuerpo = pedir(PUBLICO, "/api/v1/indicadores/resumen")
comprobar(estado == 200 and "metadatos" in json.loads(cuerpo), "indicadores públicos por el portal público")
comprobar(pedir(PUBLICO, "/api/v1/mapas/publico")[0] == 200, "mapa público por el portal")
comprobar(pedir(PUBLICO, "/api/v1/casos")[0] == 401, "los casos exigen token también por el portal")
estado, _, cuerpo = pedir(OPERADOR, "/api/v1/auth/login", "POST",
                          {"correo": ENV["IDENTIDAD_ADMIN_CORREO"], "clave": ENV["IDENTIDAD_ADMIN_CLAVE"]})
for _ in range(6):
    if estado != 429:
        break
    time.sleep(11)
    estado, _, cuerpo = pedir(OPERADOR, "/api/v1/auth/login", "POST",
                              {"correo": ENV["IDENTIDAD_ADMIN_CORREO"], "clave": ENV["IDENTIDAD_ADMIN_CLAVE"]})
comprobar(estado == 200, f"ingreso del administrador por el portal de operación ({estado})")
token = json.loads(cuerpo)["tokenAcceso"]
correlacion = "8b0c3f8e-2f7d-4b8f-9f5e-0c6c6f0c1a11"
estado, h, cuerpo = pedir(OPERADOR, "/api/v1/casos?tamano=5", cabeceras={"Authorization": f"Bearer {token}",
                                                                      "X-Correlation-Id": correlacion})
comprobar(estado == 200 and "elementos" in json.loads(cuerpo), "bandeja de casos por el portal de operación")
comprobar(h.get("X-Correlation-Id") == correlacion, "el X-Correlation-Id del portal llega y vuelve")

print("3) Denuncia anónima por la API de la dirección pública (como las apps)")
marca = f"denuncia-portal-{int(time.time())}"
estado, h, cuerpo = pedir(PUBLICO, "/api/v1/denuncias", "POST", denuncia(f"Conexión ilegal {marca}"),
                          {"Authorization": f"Bearer {token}", "Cookie": "sesion=abc"})
for _ in range(6):
    if estado != 429:
        break
    time.sleep(int(h.get("Retry-After", "10")) + 1)
    estado, h, cuerpo = pedir(PUBLICO, "/api/v1/denuncias", "POST", denuncia(f"Conexión ilegal {marca}"))
comprobar(estado == 202, f"denuncia aceptada ({estado})")
registros = compose("logs", "--no-log-prefix", "portal-publico").stdout
comprobar("/api/v1/denuncias" not in registros, "nginx no registra las denuncias")
comprobar(not re.search(r"\b\d{1,3}(\.\d{1,3}){3}\b", registros + compose("logs", "--no-log-prefix",
                                                                         "portal-operador").stdout),
          "ningún registro de los portales contiene direcciones IP")
comprobar('"ruta":"/api/v1/indicadores/resumen"' in registros, "los registros de los portales son JSON")

print("4) Límite de abuso detrás del proxy")
# Directo al gateway: X-Forwarded-For distinto en cada intento no evade el límite (5/min).
# Ventana fija: 11 intentos garantizan que alguna ventana reciba más de 5.
directos = [pedir(GATEWAY, "/api/v1/denuncias", "POST", denuncia(f"Prueba de límite {i} {marca}"),
                  {"X-Forwarded-For": f"203.0.113.{i}"})[0] for i in range(11)]
comprobar(429 in directos, f"X-Forwarded-For falsificado no evade el límite ({directos})")
# Por el portal: el límite se sigue aplicando por cliente.
por_portal = [pedir(PUBLICO, "/api/v1/denuncias", "POST", denuncia(f"Prueba de límite {i} {marca}"))[0]
              for i in range(11)]
comprobar(429 in por_portal, f"por el portal también hay límite ({por_portal})")

terminar()
