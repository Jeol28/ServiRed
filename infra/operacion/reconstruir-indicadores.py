#!/usr/bin/env python3
"""
Verifica o reconstruye la vista de lectura de Indicadores (R14).

La vista (indicadores.vista_reportes) es una proyección de los eventos. Si se perdieron
eventos (por ejemplo, mensajes descartados de una cola de fallidos) o la proyección tuvo
un error, los indicadores, los mapas y los datos abiertos quedan desalineados con los
datos de origen. Este script los compara y, con --aplicar, reescribe la vista desde el
origen: reportes y restablecimientos, votos de CORTE que cuentan (ADR-22) y casos.

  python3 infra/operacion/reconstruir-indicadores.py              # solo verificar
  python3 infra/operacion/reconstruir-indicadores.py --aplicar    # reconstruir

Código de salida: 0 si la vista está alineada (o se reconstruyó), 2 si al verificar hay
diferencias (sirve en una tarea programada), 1 si falló.

Solo Persistencia toca la base (ADR-02): el script llama a su operación interna
reconstruirVistaIndicadores desde dentro de su contenedor, con la clave del servicio
Indicadores de .env (o de SERVIRED_ENV=<archivo>). La clave va por la entrada estándar
de curl, no en la línea de comandos. Mientras se reescribe, la proyección de eventos
espera; los eventos que lleguen después no deshacen el resultado (la vista ignora los más
viejos que lo que ya tiene).
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


def llamar(aplicar, umbral):
    env = leer_env()
    correlacion = str(uuid.uuid4())
    cabeceras = f"X-Clave-Servicio: {env['PERSISTENCIA_CLAVE_INDICADORES']}\nX-Correlation-Id: {correlacion}\n"
    cuerpo = json.dumps({"aplicar": aplicar, "umbralValidacion": umbral})
    r = subprocess.run(["docker", "compose", "exec", "-T", "persistencia", "curl", "-sS", "--fail-with-body",
                        "-X", "POST", "http://localhost:8080/interno/v1/indicadores/reconstruccion",
                        "-H", "@-", "-H", "content-type: application/json", "-d", cuerpo],
                       cwd=RAIZ, input=cabeceras, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(f"Persistencia no respondió bien (correlationId {correlacion}):\n{r.stdout}{r.stderr}")
    return json.loads(r.stdout), correlacion


def imprimir(r):
    print(f"Reportes en origen: {r['reportesEnOrigen']} · filas en la vista: {r['filasEnVista']}")
    print(f"Faltan en la vista: {r['faltantes']} · sobran (sin reporte): {r['sobrantes']} · "
          f"con valores distintos: {r['distintas']}")
    for columna, n in (r.get("distintasPorColumna") or {}).items():
        print(f"  {columna}: {n}")


def main():
    p = argparse.ArgumentParser(description="Verificar o reconstruir la vista de Indicadores (R14)")
    p.add_argument("--aplicar", action="store_true", help="reescribir la vista desde los datos de origen")
    p.add_argument("--umbral", type=int, default=3, help="confirmaciones netas para validar (ADR-08)")
    args = p.parse_args()

    resultado, correlacion = llamar(args.aplicar, args.umbral)
    alineada = resultado["faltantes"] == 0 and resultado["sobrantes"] == 0 and resultado["distintas"] == 0
    imprimir(resultado)
    if not args.aplicar:
        print("La vista está alineada con el origen." if alineada
              else "Hay diferencias. Reconstruya con: reconstruir-indicadores.py --aplicar")
        sys.exit(0 if alineada else 2)
    despues, _ = llamar(False, args.umbral)
    ok = despues["faltantes"] == 0 and despues["sobrantes"] == 0 and despues["distintas"] == 0
    print(f"\nVista reconstruida (correlationId {correlacion}). Verificación posterior: "
          + ("alineada." if ok else "aún hay diferencias:"))
    if not ok:
        imprimir(despues)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
