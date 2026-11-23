#!/usr/bin/env python3
"""
Anclas externas de la cadena de auditoría (ADR-31), contra el compose.

Sin AUDITORIA_ANCLAS_GITHUB_* (el entorno local), el ancla del día se fija igual y la sirve la
API pública; la publicación en GitHub y la detección de una reescritura completa de un segmento
se prueban en AnclasAuditoriaIntegracionTest (con GitHub simulado).

  1. Fijar el ancla de hoy: secuencia y hash del último registro de la cadena.
  2. La API pública la entrega sin token, por el gateway y por el portal público.
  3. Fijarla de nuevo no crea otra ni la cambia.
  4. La verificación compara las anclas con la cadena actual y coinciden.

Uso (desde la raíz):  python3 pruebas/e2e/anclas-auditoria.py
"""
import json
import subprocess
import sys
import urllib.request

from comun import RAIZ, comprobar, entrar_como_admin, http, sql, terminar


def script(*args):
    return subprocess.run([sys.executable, "infra/operacion/auditoria-anclas.py", *args], cwd=RAIZ,
                          capture_output=True, text=True)


entrar_como_admin()   # genera actividad auditada: la cadena no está vacía

print("1. Fijar el ancla de hoy")
anclado = script("anclar")
print("     " + anclado.stdout.strip().replace("\n", "\n     "))
comprobar(anclado.returncode == 0 and "Ancla " in anclado.stdout, "se fija el ancla del día")
fecha, secuencia, hash_ = sql("SELECT fecha || '|' || secuencia || '|' || hash FROM auditoria.anclas "
                              "ORDER BY fecha DESC LIMIT 1").split("|")
hash_en_cadena = sql(f"""SELECT coalesce((SELECT hash FROM auditoria.registros WHERE secuencia = {secuencia}),
                         (SELECT hash_final FROM auditoria.segmentos WHERE secuencia_hasta = {secuencia}))""")
if hash_en_cadena:
    comprobar(hash_ == hash_en_cadena, f"es el hash del registro {secuencia} de la cadena")
else:
    # El registro ya se archivó dentro de un segmento (ADR-29): lo compara la verificación (paso 4),
    # que descarga el segmento.
    comprobar(sql(f"SELECT count(*) FROM auditoria.segmentos WHERE {secuencia} BETWEEN secuencia_desde "
                  "AND secuencia_hasta") == "1", f"el registro {secuencia} está archivado en un segmento")

print("2. Pública, sin token")
estado, _, anclas = http("GET", "/api/v1/auditoria/anclas")
comprobar(estado == 200 and any(a["hash"] == hash_ and a["fecha"] == fecha for a in anclas),
          "el gateway la entrega sin token")
comprobar(all(set(a) <= {"fecha", "secuencia", "hash", "registros", "segmentos", "publicadaEn", "destino", "referencia"}
              for a in anclas), "solo hashes, conteos y dónde se publicó")
with urllib.request.urlopen("http://localhost:4300/api/v1/auditoria/anclas", timeout=10) as r:
    comprobar(any(a["hash"] == hash_ for a in json.loads(r.read())), "y también por el portal público")
comprobar(http("GET", "/api/v1/auditoria/registros")[0] == 401, "el resto de la auditoría sigue exigiendo rol")

print("3. Una por día")
script("anclar")
comprobar(sql(f"SELECT count(*) || '|' || max(hash) FROM auditoria.anclas WHERE fecha = '{fecha}'") == f"1|{hash_}",
          "fijarla de nuevo no crea otra ni la cambia")

print("4. Verificación")
verificacion = script("verificar")
print("     " + verificacion.stdout.strip().replace("\n", "\n     "))
comprobar(verificacion.returncode == 0 and "coinciden" in verificacion.stdout, "las anclas coinciden con la cadena")
comprobar("sin destino externo" in verificacion.stdout,
          "sin GitHub configurado lo dice: la fuente es la base, menos confiable que la externa")

terminar()
