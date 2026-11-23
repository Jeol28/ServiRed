#!/usr/bin/env python3
"""
Anclas externas de la cadena de auditoría (ADR-31).

Persistencia fija cada día el último hash de la cadena y lo publica fuera del sistema: un
archivo en GitHub (rama anclas-auditoria, si AUDITORIA_ANCLAS_GITHUB_* está configurado) y la
API pública GET /api/v1/auditoria/anclas. Este script permite revisarlo:

  python3 infra/operacion/auditoria-anclas.py listar
  python3 infra/operacion/auditoria-anclas.py anclar        # fijar y publicar la de hoy ahora
  python3 infra/operacion/auditoria-anclas.py verificar     # comparar lo publicado con la cadena actual

Código de salida: 0 si todo coincide, 2 si alguna ancla publicada no coincide (posible
reescritura de la historia) o la cadena está rota, 1 si falló.

La verificación lee las anclas de GitHub (no de la base, que podría estar alterada) y busca el
hash de cada secuencia en la base o dentro de su segmento archivado. Además, cualquiera puede
comparar por su cuenta: las anclas son públicas.

Llama a Persistencia desde dentro de su contenedor con la clave del servicio Auditoría de
.env (o de SERVIRED_ENV=<archivo>), que va por la entrada estándar de curl.
"""
import argparse
import json
import os
import subprocess
import sys
import uuid
from pathlib import Path

RAIZ = Path(__file__).resolve().parents[2]


def leer_env():
    valores = {}
    for linea in (RAIZ / os.environ.get("SERVIRED_ENV", ".env")).read_text(encoding="utf-8").splitlines():
        if "=" in linea and not linea.lstrip().startswith("#"):
            clave, valor = linea.split("=", 1)
            valor = valor.strip()
            if len(valor) >= 2 and valor[0] == valor[-1] and valor[0] in "'\"":
                valor = valor[1:-1]
            valores[clave.strip()] = valor
    return valores


def llamar(metodo, ruta):
    env = leer_env()
    correlacion = str(uuid.uuid4())
    cabeceras = f"X-Clave-Servicio: {env['PERSISTENCIA_CLAVE_AUDITORIA']}\nX-Correlation-Id: {correlacion}\n"
    r = subprocess.run(["docker", "compose", "exec", "-T", "persistencia", "curl", "-sS", "--fail-with-body", "-X",
                        metodo, f"http://localhost:8080{ruta}", "-H", "@-", "--max-time", "600"],
                       cwd=RAIZ, input=cabeceras, capture_output=True, text=True)
    if r.returncode != 0:
        if "Cadena de auditoría rota" in r.stdout:
            print("DETENIDO: la cadena de auditoría está rota; no se ancla un estado alterado.")
            sys.exit(2)
        raise SystemExit(f"Persistencia no respondió bien (correlationId {correlacion}):\n{r.stdout}{r.stderr}")
    return json.loads(r.stdout) if r.stdout.strip() else None


def listar(_args):
    anclas = llamar("GET", "/interno/v1/auditoria/anclas")
    if not anclas:
        print("Todavía no hay anclas.")
        return 0
    print(f"{'fecha':10}  {'secuencia':>9}  {'hash':16}  publicada en")
    for a in anclas:
        donde = a.get("referencia") or "sin publicar afuera"
        print(f"{a['fecha']:10}  {a['secuencia']:>9}  {a['hash'][:16]}  {donde}")
    return 0


def anclar(_args):
    a = llamar("POST", "/interno/v1/auditoria/anclas")
    if not a:
        print("La cadena está vacía: no hay nada que anclar.")
        return 0
    print(f"Ancla {a['fecha']}: secuencia {a['secuencia']}, hash {a['hash']}.")
    print(f"Publicada afuera: {a.get('referencia') or 'no (sin destino configurado o GitHub no respondió)'}")
    return 0


def verificar(_args):
    v = llamar("POST", "/interno/v1/auditoria/anclas/verificacion")
    print(f"Fuente: {v['fuente']} ({v['anclas']} ancla(s)).")
    if v["coinciden"]:
        print("Todas las anclas publicadas coinciden con la cadena actual.")
        return 0
    print("NO COINCIDEN (posible reescritura de la historia):")
    for d in v["distintas"]:
        print(f"  {d['fecha']}  secuencia {d['secuencia']}: publicado {d['hashPublicado'][:16]}…, "
              f"hoy {(d.get('hashActual') or '—')[:16]}… ({d['ubicacion']})")
    return 2


def main():
    p = argparse.ArgumentParser(description="Anclas externas de la cadena de auditoría (ADR-31)")
    sub = p.add_subparsers(dest="comando", required=True)
    sub.add_parser("listar", help="anclas fijadas y dónde se publicaron").set_defaults(f=listar)
    sub.add_parser("anclar", help="fijar y publicar el ancla de hoy").set_defaults(f=anclar)
    sub.add_parser("verificar", help="comparar lo publicado afuera con la cadena actual").set_defaults(f=verificar)
    args = p.parse_args()
    sys.exit(args.f(args))


if __name__ == "__main__":
    main()
