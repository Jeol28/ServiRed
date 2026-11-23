#!/usr/bin/env python3
"""
Archivo del registro de auditoría conservando la cadena de huellas (ADR-29).

Contra el compose:
  1. Se archiva todo lo registrado hasta ahora (infra/operacion/auditoria-archivo.py). Los
     registros salen de la base a segmentos en el almacén.
  2. La integridad (API pública, administrador) sigue íntegra y cuenta lo archivado; el
     total de la cadena no baja.
  3. La actividad nueva se encadena con el último registro archivado y la cadena sigue íntegra.
  4. La verificación profunda descarga cada segmento, compara su SHA-256 y recalcula todas
     sus huellas.

Archiva de verdad el registro de la base local: úsela solo en un entorno de pruebas.
Uso (desde la raíz):  python3 pruebas/e2e/archivo-auditoria.py
"""
import subprocess
import sys
from datetime import datetime, timezone

from comun import RAIZ, comprobar, entrar_como_admin, esperar, http, sql, terminar


def script(*args):
    return subprocess.run([sys.executable, "infra/operacion/auditoria-archivo.py", *args], cwd=RAIZ,
                          capture_output=True, text=True)


admin = entrar_como_admin()["tokenAcceso"]


def integridad():
    estado, _, v = http("GET", "/api/v1/auditoria/integridad", token=admin)
    return v if estado == 200 else {}


print("0. Antes de archivar")
antes = integridad()
comprobar(antes.get("integra") is True, f"la cadena está íntegra ({antes.get('registros')} registros)")

print("1. Archivar todo lo registrado hasta ahora")
hasta = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
archivado = script("archivar", "--hasta", hasta)
print("     " + archivado.stdout.strip().replace("\n", "\n     "))
comprobar(archivado.returncode == 0 and "Segmentos nuevos:" in archivado.stdout, "el archivado termina sin detenerse")
segmentos = int(sql("SELECT count(*) FROM auditoria.segmentos"))
comprobar(segmentos >= 1, f"hay segmentos en el índice ({segmentos})")
claves = sql("SELECT string_agg(clave_objeto, ',' ORDER BY id) FROM auditoria.segmentos").split(",")
comprobar(all(c.startswith("auditoria/segmentos/") for c in claves), "los archivos están bajo auditoria/segmentos/")
comprobar(int(sql(f"SELECT count(*) FROM auditoria.registros WHERE registrado_en < '{hasta}'")) == 0,
          "lo registrado antes del límite ya no está en la base")

print("2. La cadena sigue íntegra y completa")
despues = integridad()
comprobar(despues.get("integra") is True, "íntegra después de archivar")
comprobar(despues.get("registros", 0) >= antes.get("registros", 0), "el total de la cadena no baja")
comprobar(despues.get("registrosArchivados", 0) >= 1 and despues.get("segmentos") == segmentos,
          f"cuenta lo archivado ({despues.get('registrosArchivados')} en {despues.get('segmentos')} segmento(s))")

print("3. Lo nuevo se encadena con lo archivado")
http("POST", "/api/v1/auth/login", {"correo": "nadie@ejemplo.co", "clave": "una clave que no existe"})
comprobar(esperar(lambda: integridad().get("registrosEnBase", 0) >= 1, 20), "llegó un registro nuevo")
ultimo_archivado = sql("SELECT hash_final FROM auditoria.segmentos ORDER BY secuencia_hasta DESC LIMIT 1")
primero_en_base = sql("SELECT hash_anterior FROM auditoria.registros ORDER BY secuencia LIMIT 1")
comprobar(primero_en_base == ultimo_archivado, "el primer registro en la base apunta al último archivado")
comprobar(integridad().get("integra") is True, "la cadena sigue íntegra")

print("4. Verificación profunda del archivo")
verificacion = script("verificar")
print("     " + verificacion.stdout.strip())
comprobar(verificacion.returncode == 0, "cada segmento coincide con su SHA-256 y sus huellas se recalculan")
listado = script("listar")
comprobar(listado.returncode == 0 and "segmento(s)" in listado.stdout, "listar muestra los segmentos")

terminar()
