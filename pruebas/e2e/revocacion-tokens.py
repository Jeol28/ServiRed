#!/usr/bin/env python3
"""
Lista de revocación de tokens de acceso por jti en el gateway (ADR-26).

Antes, un token de acceso (15 min) seguía sirviendo hasta expirar aunque la cuenta se
desactivara, se cambiara la clave o se cerrara la sesión. Comprueba contra el compose:

  1. Cerrar sesión en un dispositivo invalida su token de acceso en segundos; el token del
     otro dispositivo de la misma cuenta sigue sirviendo.
  2. Renovar la sesión no invalida el token anterior (rotación normal).
  3. Desactivar la cuenta invalida todos sus tokens vigentes, también el anterior a una
     renovación, y el refresco ya no renueva.
  4. Cambiar la clave invalida los tokens de la cuenta.
  5. La cuenta reactivada vuelve a entrar y su token nuevo sirve.

Uso (desde la raíz):  python3 pruebas/e2e/revocacion-tokens.py
"""
import time

from comun import comprobar, entrar_como_admin, http, registrar_y_entrar, terminar

PLAZO = 15   # segundos: el gateway sincroniza cada 5 s


def estado_con(token):
    return http("GET", "/api/v1/cuentas/yo", token=token)[0]


def rechazado_en(token, plazo=PLAZO):
    """Segundos hasta que el gateway rechaza el token (401), o None si no lo rechazó a tiempo."""
    inicio = time.monotonic()
    while time.monotonic() - inicio < plazo:
        if estado_con(token) == 401:
            return time.monotonic() - inicio
        time.sleep(0.25)
    return None


def entrar(correo, clave="una clave larga de prueba"):
    estado, _, s = http("POST", "/api/v1/auth/login", {"correo": correo, "clave": clave})
    if estado != 200:
        raise SystemExit(f"No se pudo iniciar sesión ({estado}): {s}")
    return s


print("1. Cerrar sesión en un dispositivo")
telefono, correo = registrar_y_entrar()
portal = entrar(correo)
comprobar(estado_con(telefono["tokenAcceso"]) == 200 and estado_con(portal["tokenAcceso"]) == 200,
          "los dos dispositivos tienen un token que sirve")
http("POST", "/api/v1/auth/cerrar-sesion", {"tokenRefresco": telefono["tokenRefresco"]})
t = rechazado_en(telefono["tokenAcceso"])
comprobar(t is not None, f"el token del teléfono deja de servir ({'%.1f s' % t if t is not None else 'no'})")
comprobar(estado_con(portal["tokenAcceso"]) == 200, "el del portal sigue sirviendo")

print("2. Renovar no invalida el token anterior")
estado, _, renovada = http("POST", "/api/v1/auth/refrescar", {"tokenRefresco": portal["tokenRefresco"]})
comprobar(estado == 200, f"renovación ({estado})")
time.sleep(7)
comprobar(estado_con(portal["tokenAcceso"]) == 200 and estado_con(renovada["tokenAcceso"]) == 200,
          "el token anterior y el nuevo sirven")

print("3. Desactivar la cuenta")
admin = entrar_como_admin()["tokenAcceso"]
id_cuenta = http("GET", "/api/v1/cuentas/yo", token=renovada["tokenAcceso"])[2]["idCuenta"]
estado, _, _ = http("PATCH", f"/api/v1/cuentas/{id_cuenta}", {"activa": False}, token=admin)
comprobar(estado == 200, f"el administrador la desactiva ({estado})")
t = rechazado_en(renovada["tokenAcceso"])
comprobar(t is not None, f"el token vigente deja de servir ({'%.1f s' % t if t is not None else 'no'})")
comprobar(rechazado_en(portal["tokenAcceso"], 2) is not None, "también el anterior a la renovación")
comprobar(http("POST", "/api/v1/auth/refrescar", {"tokenRefresco": renovada["tokenRefresco"]})[0] == 401,
          "el refresco ya no renueva")
comprobar(estado_con(admin) == 200, "el token del administrador no se afecta")

print("4. Cambiar la clave")
otra, correo_otra = registrar_y_entrar()
estado, _, _ = http("PATCH", "/api/v1/cuentas/yo", {"claveActual": "una clave larga de prueba",
                                                     "claveNueva": "otra clave larga distinta"}, token=otra["tokenAcceso"])
comprobar(estado == 200, f"cambio de clave ({estado})")
t = rechazado_en(otra["tokenAcceso"])
comprobar(t is not None, f"el token anterior al cambio deja de servir ({'%.1f s' % t if t is not None else 'no'})")
nueva = entrar(correo_otra, "otra clave larga distinta")
comprobar(estado_con(nueva["tokenAcceso"]) == 200, "con la clave nueva entra y su token sirve")

print("5. Reactivar la cuenta")
http("PATCH", f"/api/v1/cuentas/{id_cuenta}", {"activa": True}, token=admin)
de_nuevo = entrar(correo)
comprobar(estado_con(de_nuevo["tokenAcceso"]) == 200, "la cuenta reactivada entra y su token nuevo sirve")

terminar()
