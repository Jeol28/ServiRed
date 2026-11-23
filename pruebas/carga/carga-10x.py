#!/usr/bin/env python3
"""
Prueba de carga 10× en una zona afectada (R19, RNF-E2).

RNF-E2: «El sistema debe soportar incrementos de tráfico de al menos diez veces la carga
promedio en una zona afectada sin pérdida de reportes aceptados».

Escenario (E7): un corte masivo en una zona de Bogotá. Muchos ciudadanos distintos
reportan a la vez; la mitad adjunta una foto (--fotos) y cada uno, después de reportar,
consulta su reporte y el mapa público.
La carga es de lazo abierto: los reportes salen a la tasa fijada aunque el sistema se
demore, como en un pico real (los ciudadanos no esperan a que el sistema se desocupe).

  carga promedio = --promedio reportes por minuto en la zona (por defecto 60, supuesto:
                   el SAD no fija la cifra; ajústela con el dato real de la operación)
  pico           = --factor × promedio (por defecto 10×) durante --duracion segundos

Comprueba:
  1. Sin pérdida: cada reporte que recibió 201 está en la base, publicó su
     reporte.creado (outbox), abrió su caso en Atención, llegó a la vista de Indicadores
     y quedó en Auditoría. Las colas de fallidos no crecen.
  2. Sin duplicados: los reintentos de la app (mismo X-Correlation-Id ante un error de
     red o un 5xx) no crean un segundo reporte.
  3. Se atiende el pico: ningún reporte termina rechazado por el sistema (5xx, timeout)
     tras los reintentos.
  4. RNF-R1: p95 de la creación ≤ 2 s.  RNF-R3: cada reporte visible para el operador
     (caso abierto) en ≤ 10 s.
  5. Fotos: cada foto aceptada (201) tiene su referencia en la base, se puede descargar,
     es un JPEG y llegó sin EXIF (ni GPS ni modelo del teléfono). Ningún servicio se
     reinició ni se quedó sin memoria (el saneamiento decodifica cada foto completa).

Las fotos son como las que mandan las apps: JPEG de 2560 × 1920 (el lado máximo que
dejan las apps), calidad 85, entre 0,9 y 1,3 MB. Llevan EXIF con GPS y modelo del teléfono,
como si un cliente no los hubiera quitado. Se generan con Pillow.

Las cuentas de prueba se crean clonando una cuenta registrada por la API (misma clave) y
se envejecen 2 días, como en las e2e. Cada cuenta envía a lo sumo 4 reportes por minuto y
9 por hora, por debajo del límite por cuenta (ADR-22). El inicio de sesión está limitado
a 20 por minuto por IP y toda la prueba sale de una sola IP, así que preparar las cuentas
toma unos minutos.

Escribe en la base y la llena de reportes: solo contra el compose local. Se niega a correr
si PERSISTENCIA_DB_URL apunta a Supabase.

  SERVIRED_ENV=.env.antes-supabase python3 pruebas/carga/carga-10x.py
  SERVIRED_ENV=.env.antes-supabase python3 pruebas/carga/carga-10x.py --promedio 30 --duracion 180
"""
import argparse
import base64
import io
import json
import math
import random
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "e2e"))
from comun import ENV, RABBIT_API, _http, http, http_crudo, multipart, sql  # noqa: E402

import urllib.request  # noqa: E402

ZONA = {"latitud": (4.60, 4.70), "longitud": (-74.10, -74.04), "municipio": "11001"}   # Bogotá
CLAVE = "una clave larga de carga"
BOGOTA = timezone(timedelta(hours=-5))
MODELO = "TelefonoDeCargaServiRed"


def generar_fotos():
    """Tres JPEG como los de las apps (2560 × 1920, calidad 85), con EXIF de GPS y modelo."""
    try:
        from PIL import Image, ImageChops
    except ImportError:
        raise SystemExit("Las fotos de la prueba se generan con Pillow: pip install pillow (o use --fotos 0)")
    ancho, alto = 2560, 1920
    gradiente = Image.linear_gradient("L").resize((ancho, alto))
    base = Image.merge("RGB", (gradiente, gradiente.rotate(180), gradiente.transpose(Image.Transpose.FLIP_LEFT_RIGHT)))
    fotos = []
    for sigma in (12, 14, 18):
        imagen = ImageChops.add(base, Image.effect_noise((ancho, alto), sigma).convert("RGB"), scale=2.0, offset=-60)
        exif = Image.Exif()
        exif[0x0110] = MODELO                                   # Model
        exif[0x8825] = {1: "N", 2: (4.0, 38.0, 15.0), 3: "W", 4: (74.0, 3.0, 50.0)}   # GPSInfo
        salida = io.BytesIO()
        imagen.save(salida, "JPEG", quality=85, exif=exif)
        fotos.append(salida.getvalue())
    return fotos


def servicios_reiniciados():
    """Reinicios y muertes por memoria de cada contenedor del compose."""
    import subprocess
    nombres = subprocess.run(["docker", "ps", "-a", "--filter", "name=servired-", "--format", "{{.Names}}"],
                             capture_output=True, text=True).stdout.split()
    estado = {}
    for n in nombres:
        r = subprocess.run(["docker", "inspect", "-f", "{{.RestartCount}} {{.State.OOMKilled}} {{.State.StartedAt}}",
                            n], capture_output=True, text=True).stdout.split()
        if r:
            estado[n] = tuple(r)
    return estado


def percentil(valores, p):
    if not valores:
        return float("nan")
    orden = sorted(valores)
    return orden[min(len(orden) - 1, math.ceil(p / 100 * len(orden)) - 1)]


def rabbit_dlq():
    auth = base64.b64encode(f"{ENV['RABBITMQ_USUARIO']}:{ENV['RABBITMQ_CLAVE']}".encode()).decode()
    r = urllib.request.Request(f"{RABBIT_API}/queues/%2F", headers={"Authorization": f"Basic {auth}"})
    with urllib.request.urlopen(r, timeout=15) as x:
        return {c["name"]: c.get("messages", 0) for c in json.loads(x.read()) if c["name"].endswith(".dlq")}


class Resultados:
    def __init__(self):
        self.candado = threading.Lock()
        self.aceptados = {}          # idReporte -> correlación
        self.finales = {}            # estado final -> cantidad
        self.reintentos = 0
        self.lat_creacion = []       # ms del intento que respondió
        self.lat_lecturas = {"reporte": [], "mapa": []}
        self.lecturas_fallidas = 0
        self.fotos = {}               # estado final de la subida -> cantidad
        self.fotos_aceptadas = []     # (idReporte, url, token)
        self.lat_fotos = []
        self.atraso_max = 0.0        # cuánto se atrasó el generador respecto a lo planeado

    def sumar(self, **k):
        with self.candado:
            for clave, valor in k.items():
                setattr(self, clave, getattr(self, clave) + valor)


def crear_cuentas(corrida, n):
    """Registra una cuenta plantilla por la API y la clona n veces en la base (misma clave)."""
    plantilla = f"carga.{corrida}.plantilla@ejemplo.co"
    estado, _, cuerpo = http("POST", "/api/v1/auth/registro", {"correo": plantilla, "clave": CLAVE,
                             "nombre": "Carga", "aceptaTratamientoDatos": True})
    if estado not in (200, 201):
        raise SystemExit(f"No se pudo registrar la cuenta plantilla ({estado}): {cuerpo}")
    sql(f"""INSERT INTO identidad.cuentas (id, correo, hash_clave, rol, nombre, activa, consentimiento_datos_en,
                                           creada_en, actualizada_en)
            SELECT gen_random_uuid(), 'carga.{corrida}.' || i || '@ejemplo.co', p.hash_clave, 'CIUDADANO',
                   'Carga', true, now(), now() - interval '2 days', now()
            FROM identidad.cuentas p, generate_series(1, {n}) i WHERE p.correo = '{plantilla}'""")
    creadas = int(sql(f"SELECT count(*) FROM identidad.cuentas WHERE correo LIKE 'carga.{corrida}.%' "
                      "AND correo NOT LIKE '%plantilla%'") or 0)
    if creadas != n:
        raise SystemExit(f"Se esperaban {n} cuentas de carga y hay {creadas}")


def iniciar_sesiones(corrida, n):
    """Inicia sesión con cada cuenta respetando el límite de /auth por IP (espera Retry-After)."""
    tokens, inicio = [], time.time()
    for i in range(1, n + 1):
        while True:
            estado, cab, s = _http("POST", "/api/v1/auth/login",
                                   {"correo": f"carga.{corrida}.{i}@ejemplo.co", "clave": CLAVE}, None, None, None)
            if estado == 200:
                tokens.append(s["tokenAcceso"])
                break
            if estado != 429:
                raise SystemExit(f"Inicio de sesión de la cuenta {i} falló ({estado}): {s}")
            time.sleep(int(cab.get("Retry-After", "5")) + 1)
        if i % 20 == 0 or i == n:
            print(f"     {i}/{n} sesiones ({time.time() - inicio:.0f} s)", flush=True)
    return tokens


def subir_foto(token, id_reporte, foto, res, intentos_max=3):
    """Como la app: un X-Correlation-Id por foto, el mismo en los reintentos."""
    correlacion = str(uuid.uuid4())
    cuerpo, tipo = multipart([("foto", "foto.jpg", "image/jpeg", foto)])
    estado, r = None, None
    for intento in range(intentos_max):
        t0 = time.perf_counter()
        try:
            estado, _, r = http_crudo("POST", f"/api/v1/reportes/{id_reporte}/evidencias", cuerpo, tipo, token,
                                      {"X-Correlation-Id": correlacion})
        except Exception:
            estado = "sin respuesta"
        if intento == 0:
            with res.candado:
                res.lat_fotos.append((time.perf_counter() - t0) * 1000)
        if estado == 201 or (isinstance(estado, int) and estado < 500 and estado != 408):
            break
        res.sumar(reintentos=1)
        time.sleep(0.5 * 2 ** intento)
    with res.candado:
        res.fotos[estado] = res.fotos.get(estado, 0) + 1
        if estado == 201:
            for e in json.loads(r)["evidencias"]:
                res.fotos_aceptadas.append((id_reporte, e["url"], token))


def un_ciudadano(token, res, consultas, fotos, fraccion_fotos, intentos_max=3):
    """Lo que hace la app: crea el reporte (reintenta con la misma correlación) y consulta."""
    correlacion = str(uuid.uuid4())
    cuerpo = {
        "tipoServicio": random.choices(["ENERGIA", "ACUEDUCTO"], weights=[8, 2])[0],
        "ubicacion": {"latitud": round(random.uniform(*ZONA["latitud"]), 6),
                      "longitud": round(random.uniform(*ZONA["longitud"]), 6)},
        "inicioEstimado": (datetime.now(BOGOTA) - timedelta(minutes=random.randint(5, 90))).isoformat(
            timespec="seconds"),
        "magnitud": random.choice(["VIVIENDA", "CUADRA", "BARRIO"]),
    }
    estado, r = None, None
    for intento in range(intentos_max):
        t0 = time.perf_counter()
        try:
            estado, _, r = _http("POST", "/api/v1/reportes", cuerpo, correlacion, token, None)
        except Exception:   # timeout o conexión rechazada: la app reintenta igual
            estado = "sin respuesta"
        if intento == 0 or estado == 201:
            with res.candado:
                if intento == 0:
                    res.lat_creacion.append((time.perf_counter() - t0) * 1000)
        if estado == 201 or (isinstance(estado, int) and estado < 500 and estado != 408):
            break
        res.sumar(reintentos=1)
        time.sleep(0.5 * 2 ** intento)
    with res.candado:
        res.finales[estado] = res.finales.get(estado, 0) + 1
        if estado == 201:
            res.aceptados[r["idReporte"]] = correlacion
    if estado != 201:
        return
    if fotos and random.random() < fraccion_fotos:
        subir_foto(token, r["idReporte"], random.choice(fotos), res)
    if not consultas:
        return
    for nombre, ruta, tk in (("reporte", f"/api/v1/reportes/{r['idReporte']}", token),
                             ("mapa", "/api/v1/mapas/publico?tipoServicio=ENERGIA", None)):
        t0 = time.perf_counter()
        try:
            e, _, _ = _http("GET", ruta, None, None, tk, None)
        except Exception:
            e = None
        with res.candado:
            res.lat_lecturas[nombre].append((time.perf_counter() - t0) * 1000)
            if e != 200:
                res.lecturas_fallidas += 1


def generar(tokens, tasa, duracion, res, consultas, fotos, fraccion_fotos):
    """Lazo abierto: el reporte k sale en k/tasa segundos, sin importar cuánto tarden los anteriores."""
    total = int(tasa * duracion)
    inicio = time.perf_counter()
    with ThreadPoolExecutor(max_workers=200) as hilos:
        for k in range(total):
            objetivo = inicio + k / tasa
            espera = objetivo - time.perf_counter()
            if espera > 0:
                time.sleep(espera)
            else:
                res.atraso_max = max(res.atraso_max, -espera)
            hilos.submit(un_ciudadano, tokens[k % len(tokens)], res, consultas, fotos, fraccion_fotos)
            if k and k % int(tasa * 15) == 0:
                with res.candado:
                    ok = res.finales.get(201, 0)
                print(f"     t={time.perf_counter() - inicio:5.0f} s  enviados {k:5}  aceptados {ok:5}", flush=True)
    return time.perf_counter() - inicio, total


def main():
    p = argparse.ArgumentParser(description="Prueba de carga 10× en una zona afectada (R19)")
    p.add_argument("--promedio", type=float, default=60, help="reportes por minuto en la zona en un día normal")
    p.add_argument("--factor", type=float, default=10)
    p.add_argument("--duracion", type=int, default=120, help="segundos del pico")
    p.add_argument("--sin-consultas", action="store_true", help="solo crear reportes")
    p.add_argument("--fotos", type=float, default=0.5, help="fracción de reportes con una foto (0 = sin fotos)")
    p.add_argument("--espera-max", type=int, default=300, help="segundos para que se procese todo")
    args = p.parse_args()

    if "supabase" in ENV.get("PERSISTENCIA_DB_URL", ""):
        raise SystemExit("PERSISTENCIA_DB_URL apunta a Supabase: esta prueba solo corre contra la base local "
                         "(SERVIRED_ENV=.env.antes-supabase).")
    tasa = args.promedio * args.factor / 60
    total = int(tasa * args.duracion)
    # ≤ 4 por minuto por cuenta (límite 5) y ≤ 9 en toda la prueba (límite 10 por hora).
    n_cuentas = max(math.ceil(tasa * 15), math.ceil(total / 9))
    corrida = uuid.uuid4().hex[:8]
    fallos = []

    def comprobar(cond, texto):
        print(("  OK    " if cond else "  FALLA ") + texto)
        if not cond:
            fallos.append(texto)

    print(f"Carga promedio {args.promedio:g} reportes/min · pico {args.factor:g}× = {tasa * 60:g}/min "
          f"({tasa:g}/s) durante {args.duracion} s = {total} reportes · {n_cuentas} ciudadanos · corrida {corrida}")

    fotos = generar_fotos() if args.fotos > 0 else []
    if fotos:
        print(f"Fotos: {args.fotos:.0%} de los reportes, JPEG 2560 × 1920 de "
              f"{min(map(len, fotos)) / 1e6:.2f}–{max(map(len, fotos)) / 1e6:.2f} MB con EXIF")

    print("1. Preparar ciudadanos")
    crear_cuentas(corrida, n_cuentas)
    tokens = iniciar_sesiones(corrida, n_cuentas)
    dlq_antes = rabbit_dlq()
    contenedores_antes = servicios_reiniciados()

    print(f"2. Pico de {tasa:g} reportes/s")
    res = Resultados()
    real, enviados = generar(tokens, tasa, args.duracion, res, not args.sin_consultas, fotos, args.fotos)
    fin_carga = time.time()
    aceptados = res.aceptados
    print(f"     {enviados} enviados en {real:.1f} s (tasa real {enviados / real:.2f}/s, atraso máximo del "
          f"generador {res.atraso_max * 1000:.0f} ms) · finales: {res.finales} · reintentos: {res.reintentos}")
    if fotos:
        print(f"     fotos: {res.fotos}")

    print("3. Esperar a que se procese todo")
    filtro = (f"r.id_ciudadano IN (SELECT id FROM identidad.cuentas WHERE correo LIKE 'carga.{corrida}.%')")
    consultas = {
        "en la base": f"SELECT count(*) FROM reportes.reportes r WHERE {filtro}",
        "con reporte.creado publicado": f"""SELECT count(DISTINCT r.id) FROM reportes.reportes r
            JOIN infraestructura.outbox o ON o.tipo = 'reporte.creado' AND o.publicado_en IS NOT NULL
             AND o.sobre->'datos'->>'idReporte' = r.id::text WHERE {filtro}""",
        "con caso abierto": f"SELECT count(*) FROM reportes.reportes r JOIN atencion.casos c ON c.id_reporte = r.id "
                            f"WHERE {filtro}",
        "en la vista de Indicadores": f"SELECT count(*) FROM reportes.reportes r JOIN indicadores.vista_reportes v "
                                      f"ON v.id_reporte = r.id WHERE {filtro}",
        "en Auditoría": f"""SELECT count(DISTINCT r.id) FROM reportes.reportes r JOIN auditoria.registros a
            ON a.tipo = 'reporte.creado' AND a.recurso_id = r.id::text WHERE {filtro}""",
    }
    esperados = len(aceptados)
    conteos, limite = {}, time.time() + args.espera_max
    while time.time() < limite:
        conteos = {k: int(sql(q) or 0) for k, q in consultas.items()}
        if all(v >= esperados for v in conteos.values()):
            break
        time.sleep(2)
    drenado = time.time() - fin_carga
    print(f"     {conteos} (tras {drenado:.0f} s del fin del pico)")

    print("4. Resultados")
    ids_base = set(sql(f"SELECT r.id FROM reportes.reportes r WHERE {filtro}").split())
    perdidos = set(aceptados) - ids_base
    comprobar(not perdidos, f"ningún reporte aceptado se perdió: {esperados} con 201, {len(perdidos)} faltan en la base")
    for etapa, n in conteos.items():
        if etapa != "en la base":
            comprobar(n >= esperados, f"{n}/{esperados} {etapa}")
    duplicados = len(ids_base) - len(set(aceptados) & ids_base)
    comprobar(duplicados == 0, f"sin reportes duplicados ni sin confirmar: {duplicados} de más en la base "
                               f"({res.reintentos} reintentos de la app)")
    dlq_despues = rabbit_dlq()
    crecieron = {c: dlq_despues.get(c, 0) - n for c, n in dlq_antes.items() if dlq_despues.get(c, 0) > n}
    comprobar(not crecieron, f"las colas de fallidos no crecieron {crecieron or ''}".strip())
    rechazados = enviados - res.finales.get(201, 0)
    comprobar(rechazados == 0, f"se atendió el pico: {rechazados} de {enviados} sin 201 tras reintentos "
                               f"{ {k: v for k, v in res.finales.items() if k != 201} or ''}".rstrip())

    lc = res.lat_creacion
    print(f"     creación (ms): p50 {percentil(lc, 50):.0f} · p95 {percentil(lc, 95):.0f} · "
          f"p99 {percentil(lc, 99):.0f} · máx {max(lc):.0f}")
    comprobar(percentil(lc, 95) <= 2000, f"RNF-R1: p95 de la creación {percentil(lc, 95):.0f} ms ≤ 2000 ms")
    for nombre, lat in res.lat_lecturas.items():
        if lat:
            print(f"     consulta {nombre} (ms): p50 {percentil(lat, 50):.0f} · p95 {percentil(lat, 95):.0f} · "
                  f"máx {max(lat):.0f}")
    if not args.sin_consultas:
        comprobar(res.lecturas_fallidas == 0, f"consultas sin error: {res.lecturas_fallidas} fallidas")

    visibles = [float(x) for x in sql(f"""SELECT extract(epoch FROM c.abierto_en - r.fecha_registro)
        FROM reportes.reportes r JOIN atencion.casos c ON c.id_reporte = r.id WHERE {filtro}""").split()]
    if visibles:
        print(f"     visible para el operador (s): p50 {percentil(visibles, 50):.2f} · "
              f"p95 {percentil(visibles, 95):.2f} · máx {max(visibles):.2f}")
        comprobar(max(visibles) <= 10, f"RNF-R3: todos visibles para el operador en ≤ 10 s "
                                       f"({sum(v > 10 for v in visibles)} tardaron más)")

    if fotos:
        print("5. Fotos")
        aceptadas = res.fotos_aceptadas
        lf = res.lat_fotos
        print(f"     subida (ms): p50 {percentil(lf, 50):.0f} · p95 {percentil(lf, 95):.0f} · "
              f"p99 {percentil(lf, 99):.0f} · máx {max(lf):.0f}")
        rechazadas = sum(n for k, n in res.fotos.items() if k != 201)
        comprobar(rechazadas == 0, f"se aceptaron todas las fotos: {len(aceptadas)} con 201, {rechazadas} sin 201 "
                                   f"{ {k: v for k, v in res.fotos.items() if k != 201} or ''}".rstrip())
        en_base = int(sql(f"SELECT count(*) FROM reportes.evidencias e JOIN reportes.reportes r ON r.id = e.id_reporte "
                          f"WHERE {filtro}") or 0)
        comprobar(en_base == len(aceptadas), f"cada foto aceptada tiene su referencia en la base ({en_base}/{len(aceptadas)})")

        def descargar(f):
            estado, _, contenido = http_crudo("GET", f[1], None, None, f[2])
            return estado, contenido

        with ThreadPoolExecutor(max_workers=16) as hilos:
            descargas = list(hilos.map(descargar, aceptadas))
        legibles = [c for e, c in descargas if e == 200 and c[:3] == b"\xff\xd8\xff"]
        comprobar(len(legibles) == len(aceptadas), f"todas se descargan y son JPEG ({len(legibles)}/{len(aceptadas)})")
        con_metadatos = sum(1 for c in legibles if MODELO.encode() in c or b"Exif\x00\x00" in c)
        comprobar(con_metadatos == 0, f"ninguna conserva EXIF, GPS ni modelo del teléfono ({con_metadatos} con metadatos)")
        if legibles:
            print(f"     guardadas: {sum(map(len, legibles)) / len(legibles) / 1e6:.2f} MB en promedio")

    despues = servicios_reiniciados()
    cambiaron = {n: (contenedores_antes.get(n), e) for n, e in despues.items()
                 if contenedores_antes.get(n) != e and "init" not in n}
    comprobar(not cambiaron, "ningún servicio se reinició ni murió por memoria"
              + (f": {cambiaron}" if cambiaron else ""))

    print("Resultado:", "TODO OK" if not fallos else f"{len(fallos)} fallo(s)")
    sys.exit(1 if fallos else 0)


if __name__ == "__main__":
    main()
