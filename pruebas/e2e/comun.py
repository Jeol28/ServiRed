"""Utilidades compartidas por las pruebas de punta a punta (sin dependencias externas)."""
import base64
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
GATEWAY = "http://localhost:8080"
RABBIT_API = "http://127.0.0.1:15672/api"


def leer_env():
    valores = {}
    for linea in (RAIZ / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in linea and not linea.lstrip().startswith("#"):
            clave, valor = linea.split("=", 1)
            valores[clave.strip()] = valor.strip()
    return valores


ENV = leer_env()
fallos = []


def comprobar(condicion, descripcion):
    print(("  OK    " if condicion else "  FALLA ") + descripcion)
    if not condicion:
        fallos.append(descripcion)
    return condicion


def terminar():
    print("Resultado:", "TODO OK" if not fallos else f"{len(fallos)} fallo(s)")
    raise SystemExit(1 if fallos else 0)


def http(metodo, ruta, cuerpo=None, correlacion=None, token=None, cabeceras_extra=None):
    cabeceras = {"content-type": "application/json", **(cabeceras_extra or {})}
    if correlacion:
        cabeceras["X-Correlation-Id"] = correlacion
    if token:
        cabeceras["Authorization"] = f"Bearer {token}"
    peticion = urllib.request.Request(GATEWAY + ruta, method=metodo, headers=cabeceras,
                                      data=json.dumps(cuerpo).encode() if cuerpo is not None else None)
    try:
        with urllib.request.urlopen(peticion, timeout=15) as r:
            return r.status, dict(r.headers), json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        contenido = e.read()
        try:
            return e.code, dict(e.headers), json.loads(contenido or b"null")
        except json.JSONDecodeError:
            return e.code, dict(e.headers), None


def compose(*argumentos):
    return subprocess.run(["docker", "compose", *argumentos], cwd=RAIZ, capture_output=True, text=True,
                          stdin=subprocess.DEVNULL, check=False)


def sql(consulta):
    r = compose("exec", "-T", "postgres-local", "psql", "-U", ENV["POSTGRES_LOCAL_USUARIO"], "-d", "servired",
                "-tAc", consulta)
    return r.stdout.strip()


def esperar(condicion, segundos=30):
    limite = time.time() + segundos
    while time.time() < limite:
        if condicion():
            return True
        time.sleep(0.5)
    return False


def mensajes_en_cola(cola):
    auth = base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()
    peticion = urllib.request.Request(f"{RABBIT_API}/queues/%2F/{cola}/get", method="POST",
                                      headers={"Authorization": f"Basic {auth}", "content-type": "application/json"},
                                      data=json.dumps({"count": 10, "ackmode": "ack_requeue_true",
                                                       "encoding": "auto"}).encode())
    with urllib.request.urlopen(peticion, timeout=10) as r:
        return len(json.loads(r.read()))


def correo_nuevo(prefijo="ciudadano"):
    return f"{prefijo}.{uuid.uuid4().hex[:10]}@ejemplo.co"


def registrar_y_entrar(correo=None, clave="una clave larga de prueba"):
    """Registra un ciudadano y devuelve (sesión, correo)."""
    correo = correo or correo_nuevo()
    http("POST", "/api/v1/auth/registro", {"correo": correo, "clave": clave, "nombre": "Prueba e2e",
                                           "aceptaTratamientoDatos": True})
    estado, _, sesion = http("POST", "/api/v1/auth/login", {"correo": correo, "clave": clave})
    if estado != 200:
        raise SystemExit(f"No se pudo iniciar sesión ({estado}): {sesion}")
    return sesion, correo


def entrar_como_admin():
    estado, _, sesion = esperar_valor(lambda: http("POST", "/api/v1/auth/login", {
        "correo": ENV["IDENTIDAD_ADMIN_CORREO"], "clave": ENV["IDENTIDAD_ADMIN_CLAVE"]}))
    if estado != 200:
        raise SystemExit(f"No se pudo entrar como administrador ({estado}): {sesion}")
    return sesion


def esperar_valor(llamada, segundos=30):
    """Reintenta la llamada HTTP mientras responda 401/503 (p. ej. el admin aún no existe)."""
    limite = time.time() + segundos
    while True:
        resultado = llamada()
        if resultado[0] not in (401, 503) or time.time() > limite:
            return resultado
        time.sleep(1)
