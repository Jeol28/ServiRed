#!/usr/bin/env python3
"""
Prueba de punta a punta de la fase 3 (RF-11, RNF-SEG1, RNF-SEG2, RNF-SEG5)
contra el entorno de Docker Compose, siempre a través del gateway:

  1. Registro de ciudadano con autorización de datos; correo repetido -> 409.
  2. Login: JWT RS256 sin datos personales; credenciales malas -> 401 genérico.
  3. Sin token -> 401; token alterado -> 401.
  4. Mínimo privilegio: el ciudadano no ve casos ni administra cuentas.
  5. El administrador inicial crea un operador y un regulador; el operador no
     puede crear reportes y el regulador no puede modificar casos.
  6. Renovación con rotación: reutilizar un token de refresco revoca la sesión.
  7. Desactivar una cuenta impide iniciar sesión y renovar.
  8. Supresión de la cuenta propia: los datos personales quedan anonimizados.
  9. Ninguna contraseña, hash ni correo aparece en los logs.
 10. Límite de abuso en /auth (429 con Retry-After).
 11. Métricas no expuestas por el puerto público.

Uso (desde la raíz, con el compose arriba):  python3 pruebas/e2e/identidad-y-acceso.py
"""
import base64
import json

from comun import (ENV, comprobar, compose, correo_nuevo, entrar_como_admin, http, registrar_y_entrar, sql,
                   terminar)

EMPRESA = "619a2e48-402d-5782-9daf-57f16169b157"   # Enel Colombia (ADR-35)
CLAVE = "una clave larga de prueba"


def claims(jwt):
    carga = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(carga + "=" * (-len(carga) % 4)))


print("1) Registro de ciudadano")
correo = correo_nuevo()
estado, cabeceras, cuenta = http("POST", "/api/v1/auth/registro",
                                 {"correo": correo.upper(), "clave": CLAVE, "aceptaTratamientoDatos": True})
comprobar(estado == 201 and cuenta["rol"] == "CIUDADANO", "201 con rol CIUDADANO")
comprobar(cuenta["correo"] == correo, "el correo se normaliza a minúsculas")
estado, _, _ = http("POST", "/api/v1/auth/registro", {"correo": correo, "clave": CLAVE, "aceptaTratamientoDatos": True})
comprobar(estado == 409, "correo repetido -> 409")
estado, _, problema = http("POST", "/api/v1/auth/registro", {"correo": correo_nuevo(), "clave": CLAVE})
comprobar(estado == 400 and problema["errores"][0]["campo"] == "aceptaTratamientoDatos",
          "sin autorización de datos -> 400")

print("2) Inicio de sesión")
estado, cabeceras, sesion = http("POST", "/api/v1/auth/login", {"correo": correo, "clave": CLAVE})
comprobar(estado == 200 and sesion["tipoToken"] == "Bearer", "200 con token Bearer")
comprobar(cabeceras.get("Cache-Control") == "no-store", "respuesta no cacheable")
c = claims(sesion["tokenAcceso"])
comprobar(c["rol"] == "CIUDADANO" and c["sub"] == cuenta["idCuenta"], "claims sub y rol correctos")
comprobar(not {"correo", "email", "nombre"} & set(c), "el token no contiene datos personales")
comprobar(c["exp"] - c["iat"] == 900, "el token de acceso dura 15 minutos")
e1, _, p1 = http("POST", "/api/v1/auth/login", {"correo": correo, "clave": "otra clave cualquiera"})
e2, _, p2 = http("POST", "/api/v1/auth/login", {"correo": correo_nuevo(), "clave": CLAVE})
comprobar(e1 == e2 == 401 and p1["detail"] == p2["detail"], "clave mala y cuenta inexistente son indistinguibles")
token = sesion["tokenAcceso"]

print("3) Autenticación obligatoria (RNF-SEG1)")
estado, _, _ = http("GET", "/api/v1/cuentas/yo")
comprobar(estado == 401, "sin token -> 401")
partes = token.split(".")
alterado = ".".join([partes[0], base64.urlsafe_b64encode(json.dumps({**c, "rol": "ADMINISTRADOR"}).encode())
                    .decode().rstrip("="), partes[2]])
estado, _, _ = http("GET", "/api/v1/cuentas", token=alterado)
comprobar(estado == 401, "token con rol alterado -> 401 (firma inválida)")
estado, _, yo = http("GET", "/api/v1/cuentas/yo", token=token)
comprobar(estado == 200 and yo["correo"] == correo, "con token -> su propia cuenta")

print("4) Mínimo privilegio del ciudadano (RNF-SEG2)")
comprobar(http("GET", "/api/v1/casos", token=token)[0] == 403, "no ve casos")
comprobar(http("GET", "/api/v1/cuentas", token=token)[0] == 403, "no administra cuentas")

print("5) El administrador crea personal y se segregan los roles")
admin = entrar_como_admin()["tokenAcceso"]
correo_operador, correo_regulador = correo_nuevo("operador"), correo_nuevo("regulador")
estado, _, operador = http("POST", "/api/v1/cuentas", {"correo": correo_operador, "clave": CLAVE,
                                                       "rol": "OPERADOR", "idEmpresa": EMPRESA}, token=admin)
comprobar(estado == 201 and operador["idEmpresa"] == EMPRESA, "operador creado con su empresa")
estado, _, _ = http("POST", "/api/v1/cuentas", {"correo": correo_regulador, "clave": CLAVE, "rol": "REGULADOR"},
                    token=admin)
comprobar(estado == 201, "regulador creado")
estado, _, _ = http("POST", "/api/v1/cuentas", {"correo": correo_nuevo(), "clave": CLAVE, "rol": "OPERADOR"},
                    token=admin)
comprobar(estado == 400, "un operador sin empresa -> 400")
t_operador = http("POST", "/api/v1/auth/login", {"correo": correo_operador, "clave": CLAVE})[2]["tokenAcceso"]
t_regulador = http("POST", "/api/v1/auth/login", {"correo": correo_regulador, "clave": CLAVE})[2]["tokenAcceso"]
comprobar(claims(t_operador).get("emp") == EMPRESA, "el token del operador lleva su empresa")
reporte = {"tipoServicio": "GAS", "ubicacion": {"latitud": 3.42, "longitud": -76.52},
           "inicioEstimado": "2026-11-16T08:00:00-05:00", "magnitud": "VIVIENDA"}
comprobar(http("POST", "/api/v1/reportes", reporte, token=t_operador)[0] == 403, "el operador no crea reportes")
comprobar(http("PATCH", "/api/v1/casos/x", {}, token=t_regulador)[0] == 403, "el regulador no modifica casos")
comprobar(http("GET", "/api/v1/cuentas", token=t_regulador)[0] == 403, "el regulador no administra cuentas")
estado, _, pagina = http("GET", "/api/v1/cuentas?rol=OPERADOR&tamano=100", token=admin)
comprobar(estado == 200 and any(x["idCuenta"] == operador["idCuenta"] for x in pagina["elementos"]),
          "el administrador lista los operadores")

print("6) Renovación con rotación y detección de reutilización")
estado, _, renovada = http("POST", "/api/v1/auth/refrescar", {"tokenRefresco": sesion["tokenRefresco"]})
comprobar(estado == 200 and renovada["tokenRefresco"] != sesion["tokenRefresco"], "entrega un par nuevo")
estado, _, _ = http("POST", "/api/v1/auth/refrescar", {"tokenRefresco": sesion["tokenRefresco"]})
comprobar(estado == 401, "reutilizar el token anterior -> 401")
estado, _, _ = http("POST", "/api/v1/auth/refrescar", {"tokenRefresco": renovada["tokenRefresco"]})
comprobar(estado == 401, "y revoca también la sesión vigente (posible robo)")

print("7) Desactivar una cuenta")
estado, _, _ = http("PATCH", f"/api/v1/cuentas/{operador['idCuenta']}", {"activa": False}, token=admin)
comprobar(estado == 200, "el administrador desactiva al operador")
comprobar(http("POST", "/api/v1/auth/login", {"correo": correo_operador, "clave": CLAVE})[0] == 401,
          "la cuenta desactivada no inicia sesión")

print("8) Supresión de la cuenta propia (Ley 1581)")
sesion_b, correo_b = registrar_y_entrar()
estado, _, _ = http("DELETE", "/api/v1/cuentas/yo", token=sesion_b["tokenAcceso"])
comprobar(estado == 204, "204 al suprimir")
id_b = sesion_b["cuenta"]["idCuenta"]
comprobar(sql(f"SELECT correo FROM identidad.cuentas WHERE id = '{id_b}'") == f"eliminada+{id_b}@servired.invalid",
          "el correo queda anonimizado")
comprobar(http("POST", "/api/v1/auth/login", {"correo": correo_b, "clave": CLAVE})[0] == 401, "ya no inicia sesión")

print("9) Sin secretos ni datos personales en los logs")
logs = compose("logs", "--no-color", "gateway", "identidad", "persistencia").stdout
comprobar(CLAVE not in logs, "ninguna contraseña en claro")
comprobar(correo not in logs and correo_operador not in logs, "ningún correo")
comprobar("$2a$" not in logs, "ningún hash de clave")
comprobar(sesion["tokenRefresco"] not in logs and token not in logs, "ningún token")

print("10) Límite de abuso en /auth (RNF-SEG5)")
# Ventana fija de 20 por minuto: 41 intentos garantizan que alguna ventana reciba más de 20.
estados = [http("POST", "/api/v1/auth/login", {"correo": "x@ejemplo.co", "clave": "abcdefghij"},
                reintentar_429=False) for _ in range(41)]
limitados = [e for e in estados if e[0] == 429]
comprobar(bool(limitados) and "Retry-After" in limitados[0][1], "429 con Retry-After tras el límite")

print("11) Superficie pública")
comprobar(http("GET", "/actuator/prometheus")[0] == 404, "/actuator/prometheus no existe en el puerto 8080")
comprobar(http("GET", "/interno/v1/catalogo/municipio-cercano")[0] in (401, 403), "las rutas internas se niegan")

terminar()
