#!/usr/bin/env python3
"""
Archivo del registro de auditoría (ADR-29).

Persistencia archiva sola, cada día, los meses más viejos que la ventana en la base
(AUDITORIA_MESES_EN_BASE, por defecto 12). Este script permite revisarlo y hacerlo a mano:

  python3 infra/operacion/auditoria-archivo.py listar
  python3 infra/operacion/auditoria-archivo.py verificar          # profunda: descarga cada segmento
  python3 infra/operacion/auditoria-archivo.py archivar           # ahora, con la ventana configurada
  python3 infra/operacion/auditoria-archivo.py archivar --hasta 2026-06-01T00:00:00Z

Código de salida: 0 si todo está bien, 2 si la verificación encuentra un problema o el
archivado se detuvo (cadena rota), 1 si falló.

Llama a las operaciones internas de Persistencia desde dentro de su contenedor, con la clave
del servicio Auditoría de .env (o de SERVIRED_ENV=<archivo>), que va por la entrada estándar
de curl y no en la línea de comandos.
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


def llamar(metodo, ruta, cuerpo=None):
    env = leer_env()
    correlacion = str(uuid.uuid4())
    cabeceras = f"X-Clave-Servicio: {env['PERSISTENCIA_CLAVE_AUDITORIA']}\nX-Correlation-Id: {correlacion}\n"
    orden = ["docker", "compose", "exec", "-T", "persistencia", "curl", "-sS", "--fail-with-body", "-X", metodo,
             f"http://localhost:8080{ruta}", "-H", "@-", "--max-time", "3600"]
    if cuerpo is not None:
        orden += ["-H", "content-type: application/json", "-d", json.dumps(cuerpo)]
    r = subprocess.run(orden, cwd=RAIZ, input=cabeceras, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"Persistencia no respondió bien (correlationId {correlacion}):\n{r.stdout}{r.stderr}")
    return json.loads(r.stdout) if r.stdout.strip() else None


def listar(_args):
    segmentos = llamar("GET", "/interno/v1/auditoria/segmentos")
    if not segmentos:
        print("No hay segmentos archivados: todo el registro está en la base.")
        return 0
    print(f"{'id':>4} {'secuencias':>21} {'registros':>9} {'ocurridos':>23}  archivo")
    for s in segmentos:
        print(f"{s['id']:>4} {s['secuenciaDesde']:>10}-{s['secuenciaHasta']:<10} {s['registros']:>9} "
              f"{s['ocurridoDesde'][:10]} a {s['ocurridoHasta'][:10]}  {s['claveObjeto']} ({s['bytes']:,} B)")
    print(f"\n{len(segmentos)} segmento(s), {sum(s['registros'] for s in segmentos):,} registro(s) archivados.")
    return 0


def verificar(_args):
    r = llamar("POST", "/interno/v1/auditoria/archivo/verificacion")
    if r["integro"]:
        print(f"Archivo íntegro: {r['segmentos']} segmento(s), {r['registros']:,} registro(s); cada archivo coincide "
              "con su SHA-256 y todas las huellas y enlaces se recalcularon.")
        return 0
    print(f"PROBLEMA en el segmento {r['segmentoConProblema']}: {r['problema']}")
    return 2


def archivar(args):
    r = llamar("POST", "/interno/v1/auditoria/archivo", {"hasta": args.hasta} if args.hasta else None)
    print(f"Límite: {r['limite']}. Segmentos nuevos: {r['segmentosNuevos']}, registros archivados: "
          f"{r['registrosArchivados']:,}.")
    if r.get("detenidoPor"):
        print(f"DETENIDO: {r['detenidoPor']}. Verifique la integridad antes de seguir.")
        return 2
    return 0


def main():
    p = argparse.ArgumentParser(description="Archivo del registro de auditoría (ADR-29)")
    sub = p.add_subparsers(dest="comando", required=True)
    sub.add_parser("listar", help="segmentos archivados").set_defaults(f=listar)
    sub.add_parser("verificar", help="descargar cada segmento y recalcular sus huellas").set_defaults(f=verificar)
    a = sub.add_parser("archivar", help="archivar ahora")
    a.add_argument("--hasta", help="archivar todo lo registrado antes de este instante (ISO 8601, UTC)")
    a.set_defaults(f=archivar)
    args = p.parse_args()
    sys.exit(args.f(args))


if __name__ == "__main__":
    main()
