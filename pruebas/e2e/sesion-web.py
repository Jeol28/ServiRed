#!/usr/bin/env python3
"""
Sesión de los portales con el token de refresco en una cookie HttpOnly (ADR-30).

Por el nginx del portal (mismo origen que el navegador), contra el compose:
  1. El ingreso con X-Sesion-Web devuelve el acceso en el cuerpo y el refresco SOLO en una
     cookie HttpOnly, SameSite=Strict, limitada a /api/v1/auth (sin Secure en localhost).
  2. Renovar usa la cookie y la rota; el token de acceso nuevo sirve.
  3. Reusar una cookie ya rotada se rechaza (posible robo) y borra la cookie.
  4. Cerrar sesión revoca el refresco de la cookie y la borra; después no renueva.
  5. Sin la cabecera (las apps) todo sigue como antes: el refresco va en el cuerpo.

Uso (desde la raíz):  python3 pruebas/e2e/sesion-web.py
"""
import json
import urllib.error
import urllib.request

from comun import GATEWAY, comprobar, registrar_y_entrar, terminar

PORTAL = "http://localhost:4300"
CLAVE = "una clave larga de prueba"


def pedir(ruta, cuerpo=None, cookie=None, web=True, base=PORTAL, token=None, metodo="POST"):
    cabeceras = {"content-type": "application/json"}
    if web:
        cabeceras["X-Sesion-Web"] = "cookie"
    if cookie:
        cabeceras["Cookie"] = f"servired_refresco={cookie}"
    if token:
        cabeceras["Authorization"] = f"Bearer {token}"
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    r = urllib.request.Request(base + ruta, method=metodo, headers=cabeceras, data=datos)
    try:
        with urllib.request.urlopen(r, timeout=15) as x:
            return x.status, x.headers.get_all("Set-Cookie") or [], json.loads(x.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get_all("Set-Cookie") or [], None


def valor(set_cookie):
    """El valor de servired_refresco en las cabeceras Set-Cookie ('' si se borra, None si no viene)."""
    for c in set_cookie:
        if c.startswith("servired_refresco="):
            return c.split(";", 1)[0].split("=", 1)[1]
    return None


_, correo = registrar_y_entrar()

print("1. Ingreso desde el portal")
estado, cookies, s = pedir("/api/v1/auth/login", {"correo": correo, "clave": CLAVE})
comprobar(estado == 200 and s.get("tokenAcceso") and "tokenRefresco" not in s,
          "el cuerpo trae el acceso y NO el refresco")
atributos = next((c for c in cookies if c.startswith("servired_refresco=")), "")
comprobar(all(a in atributos for a in ("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth")),
          f"el refresco va en una cookie HttpOnly, SameSite=Strict, Path=/api/v1/auth ({atributos.split(';', 1)[1:]})")
comprobar("Secure" not in atributos, "sin Secure en localhost (en el servidor sí, por HTTPS)")
r1 = valor(cookies)
comprobar(bool(r1), "la cookie tiene el refresco")

print("2. Renovar con la cookie")
estado, cookies, s2 = pedir("/api/v1/auth/refrescar", {}, cookie=r1)
r2 = valor(cookies)
comprobar(estado == 200 and "tokenRefresco" not in s2 and r2 and r2 != r1, "renueva y rota la cookie")
estado, _, yo = pedir("/api/v1/cuentas/yo", None, web=False, token=s2["tokenAcceso"], metodo="GET")
comprobar(estado == 200 and yo.get("correo") == correo, "el acceso nuevo sirve")

print("3. Reusar una cookie ya rotada")
estado, cookies, _ = pedir("/api/v1/auth/refrescar", {}, cookie=r1)
comprobar(estado == 401 and valor(cookies) == "", "se rechaza y la cookie se borra")
estado, _, _ = pedir("/api/v1/auth/refrescar", {}, cookie=r2)
comprobar(estado == 401, "y, como posible robo, se revocó también la sesión vigente")

print("4. Cerrar sesión")
_, cookies, _ = pedir("/api/v1/auth/login", {"correo": correo, "clave": CLAVE})
r3 = valor(cookies)
estado, cookies, _ = pedir("/api/v1/auth/cerrar-sesion", {}, cookie=r3)
comprobar(estado == 204 and valor(cookies) == "", "cierra y borra la cookie")
comprobar(pedir("/api/v1/auth/refrescar", {}, cookie=r3)[0] == 401, "esa cookie ya no renueva")
comprobar(pedir("/api/v1/auth/refrescar", {})[0] == 401, "sin cookie no hay nada que renovar")

print("5. Las apps no cambian")
estado, cookies, s = pedir("/api/v1/auth/login", {"correo": correo, "clave": CLAVE}, web=False, base=GATEWAY)
comprobar(estado == 200 and s.get("tokenRefresco") and valor(cookies) is None, "el refresco sigue en el cuerpo")
estado, _, s = pedir("/api/v1/auth/refrescar", {"tokenRefresco": s["tokenRefresco"]}, web=False, base=GATEWAY)
comprobar(estado == 200 and s.get("tokenRefresco"), "y renuevan con el cuerpo, como antes")

terminar()
