"""Utilidades compartidas por las pruebas de punta a punta (sin dependencias externas)."""
import base64
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]
GATEWAY = "http://localhost:8080"
RABBIT_API = "http://127.0.0.1:15672/api"


def leer_env():
    """El .env de la raíz, u otro con SERVIRED_ENV (p. ej. la base local mientras .env apunta a Supabase)."""
    valores = {}
    archivo = RAIZ / os.environ.get("SERVIRED_ENV", ".env")
    for linea in archivo.read_text(encoding="utf-8").splitlines():
        if "=" in linea and not linea.lstrip().startswith("#"):
            clave, valor = linea.split("=", 1)
            valor = valor.strip()
            # Valores entre comillas (p. ej. claves de Supabase con "$"): se toman literales.
            if len(valor) >= 2 and valor[0] == valor[-1] and valor[0] in "'\"":
                valor = valor[1:-1]
            valores[clave.strip()] = valor
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


def http(metodo, ruta, cuerpo=None, correlacion=None, token=None, cabeceras_extra=None, reintentar_429=True):
    """Petición JSON al gateway. Ante un 429 espera Retry-After y reintenta (salvo que se pida lo contrario),
    para que el límite de abuso compartido entre suites no altere los resultados."""
    for _ in range(6):
        resultado = _http(metodo, ruta, cuerpo, correlacion, token, cabeceras_extra)
        if resultado[0] != 429 or not reintentar_429:
            return resultado
        time.sleep(int(resultado[1].get("Retry-After", "5")) + 1)
    return resultado


def _http(metodo, ruta, cuerpo, correlacion, token, cabeceras_extra):
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


def psql(consulta):
    """Ejecuta SQL en la base que usa persistencia: el PostgreSQL local del compose o,
    si PERSISTENCIA_DB_URL apunta a Supabase, esa base (con un cliente psql efímero)."""
    url = ENV.get("PERSISTENCIA_DB_URL", "")
    if "supabase" not in url:
        return compose("exec", "-T", "postgres-local", "psql", "-U", ENV["POSTGRES_LOCAL_USUARIO"], "-d", "servired",
                       "-tAc", consulta)
    destino = urllib.parse.urlparse(url.removeprefix("jdbc:"))
    conexion = (f"host={destino.hostname} port={destino.port or 5432} dbname={destino.path.lstrip('/')} "
                f"user={ENV['PERSISTENCIA_DB_USUARIO']} sslmode=require")
    return subprocess.run(["docker", "run", "--rm", "-e", "PGPASSWORD", "postgres:17-alpine", "psql", conexion,
                           "-tAc", consulta], capture_output=True, text=True, stdin=subprocess.DEVNULL, check=False,
                          env={**os.environ, "PGPASSWORD": ENV["PERSISTENCIA_DB_CLAVE"]})


def sql(consulta):
    return psql(consulta).stdout.strip()


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


def buscar_caso(token, id_reporte, filtro=""):
    """El caso de un reporte en la bandeja, recorriendo todas sus páginas: con muchos casos
    acumulados en la base de pruebas, uno de prioridad baja no queda en la primera."""
    for pagina in range(50):
        estado, _, p = http("GET", f"/api/v1/casos?tamano=100&pagina={pagina}{filtro}", token=token)
        if estado != 200:
            return None
        caso = next((c for c in p["elementos"] if c["idReporte"] == id_reporte), None)
        if caso is not None or (pagina + 1) * p["tamano"] >= p["total"]:
            return caso
    return None


def correo_nuevo(prefijo="ciudadano"):
    return f"{prefijo}.{uuid.uuid4().hex[:10]}@ejemplo.co"


def registrar_y_entrar(correo=None, clave="una clave larga de prueba", nueva=False):
    """Registra un ciudadano y devuelve (sesión, correo).

    Por defecto la cuenta se «envejece» 2 días antes de iniciar sesión: las pruebas modelan
    ciudadanos establecidos, cuyo voto suma a la validación (ADR-22). Con nueva=True queda
    recién creada, como un registro real de hoy."""
    correo = correo or correo_nuevo()
    http("POST", "/api/v1/auth/registro", {"correo": correo, "clave": clave, "nombre": "Prueba e2e",
                                           "aceptaTratamientoDatos": True})
    if not nueva:
        sql(f"UPDATE identidad.cuentas SET creada_en = now() - interval '2 days' WHERE correo = '{correo}'")
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


def http_crudo(metodo, ruta, cuerpo, tipo, token=None, cabeceras_extra=None):
    """Petición con cuerpo binario; devuelve (estado, cabeceras, bytes)."""
    cabeceras = {"content-type": tipo, **(cabeceras_extra or {})} if cuerpo is not None else dict(cabeceras_extra or {})
    if token:
        cabeceras["Authorization"] = f"Bearer {token}"
    peticion = urllib.request.Request(GATEWAY + ruta, method=metodo, headers=cabeceras, data=cuerpo)
    try:
        with urllib.request.urlopen(peticion, timeout=30) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def multipart(partes):
    """partes: lista de (nombre, nombre_archivo|None, tipo, bytes). Devuelve (cuerpo, content-type)."""
    limite = "----servired" + uuid.uuid4().hex
    cuerpo = b""
    for nombre, archivo, tipo, datos in partes:
        disposicion = f'form-data; name="{nombre}"' + (f'; filename="{archivo}"' if archivo else "")
        cuerpo += (f"--{limite}\r\nContent-Disposition: {disposicion}\r\nContent-Type: {tipo}\r\n\r\n").encode()
        cuerpo += datos + b"\r\n"
    cuerpo += f"--{limite}--\r\n".encode()
    return cuerpo, f"multipart/form-data; boundary={limite}"


def png_con_metadatos(ancho=16, alto=16):
    """PNG válido con chunks eXIf y tEXt que simulan GPS y modelo del teléfono."""
    import struct
    import zlib

    def chunk(tipo, datos):
        return struct.pack(">I", len(datos)) + tipo + datos + struct.pack(">I", zlib.crc32(tipo + datos) & 0xFFFFFFFF)

    filas = b"".join(b"\x00" + bytes([200, 30, 30]) * ancho for _ in range(alto))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", ancho, alto, 8, 2, 0, 0, 0))
            + chunk(b"eXIf", b"MM\x00*GPSLatitude=7.1193;GPSLongitude=-73.1227")
            + chunk(b"tEXt", b"Make\x00TelefonoDelDenunciante")
            + chunk(b"IDAT", zlib.compress(filas))
            + chunk(b"IEND", b""))
