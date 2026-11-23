#!/usr/bin/env python3
"""
Genera la migración con las empresas prestadoras REALES y su zona de cobertura (ADR-35):
una sola empresa por zona y servicio.

Zonas (las crea V20): por municipio, su cabecera urbana (U) y el resto rural (R); en Bogotá,
Medellín, Cali y Barranquilla además sus localidades o comunas (L/C), que solo tienen dueño
propio cuando difiere del de la ciudad (el aseo de Bogotá, por Áreas de Servicio Exclusivo).

Fuentes (todas abiertas):
  * Acueducto, alcantarillado y aseo: Registro Único de Prestadores (RUPS) de la
    Superservicios, datos.gov.co 4qkq-csdn (prestadores OPERATIVOS con municipio de prestación).
      - Cabecera: gana el prestador más grande (tipo de prestador, luego su clasificación por
        número de usuarios, luego en cuántos municipios presta, luego el NIT); en las capitales
        se fija a mano (AJUSTES_AAA), siempre entre los registrados allí.
      - Rural: gana el más grande de los DEMÁS registrados en el municipio; si no hay otro,
        el mismo de la cabecera.
  * Gas natural: «Tarifas aplicadas de Gas Natural» de la Superservicios, datos.gov.co
    ek3f-5wn4 (empresa y municipios de cada mercado). Se usan los mercados con tarifas en
    2022 o después; si dos empresas tienen el mismo municipio gana la que atiende más.
  * Energía: tabla armada a mano (ENERGIA) con el operador de red de cada departamento y sus
    excepciones, con las fuentes citadas en ADR-35. Es aproximada en los bordes.

Uso (desde servicios/persistencia):
    python3 herramientas/generar-prestadores.py > src/main/resources/db/migracion/V21__prestadores_reales.sql

Descarga las fuentes a ~/.cache/servired-prestadores (o las reutiliza si ya están).
"""
import collections
import json
import os
import re
import sys
import unicodedata
import urllib.request
import uuid
from pathlib import Path

CACHE = Path.home() / ".cache" / "servired-prestadores"
AQUI = Path(__file__).resolve().parent
MIGRACIONES = AQUI.parent / "src" / "main" / "resources" / "db" / "migracion"
RUPS_URL = "https://www.datos.gov.co/resource/4qkq-csdn.json?$limit=50000"
GAS_URL = ("https://www.datos.gov.co/resource/ek3f-5wn4.json?$select=empresa,nit,id_mercado,municipios,"
           "max(ano)%20as%20ultimo&$group=empresa,nit,id_mercado,municipios&$limit=5000")
# Identificador estable de cada empresa: UUID v5 de su NIT (sin dígito de verificación).
ESPACIO = uuid.UUID("6f1c2a52-6a7e-4f53-9d2b-5e0c1a8d3b11")

SERVICIOS_RUPS = {"ACUEDUCTO": "ACUEDUCTO", "ALCANTARILLADO": "ALCANTARILLADO", "ASEO": "ASEO"}
TIPO = {  # quién presta: primero las empresas, luego el municipio directo, luego las comunitarias
    "EMPRESA INDUSTRIAL Y COMERCIAL DEL ESTADO": 3, "SOCIEDADES (EMPRESA DE SERVICIOS PUBLICOS)": 3,
    "SUCURSAL SOCIEDAD EXTRANJERA": 3, "MUNICIPIO (PRESTACIÓN DIRECTA)": 2, "MUNICIPAL": 2,
}
TAMANO = {
    "MAYOR O IGUAL A 5001 USUARIOS": 3, "MAS DE 2500 SUSCRIPTORES": 3, "DESDE 2501 HASTA 5000 USUARIOS": 2,
}

# ---------------------------------------------------------------- energía (a mano, ADR-35)
# Operador de red por departamento (código DANE) y excepciones por municipio.
# Enel Colombia: Codensa se fusionó en 2022 con Emgesa, que conservó su NIT.
EMPRESAS_ENERGIA = {
    "860063875": "ENEL COLOMBIA S.A. E.S.P.",
    "890904996": "EMPRESAS PÚBLICAS DE MEDELLÍN E.S.P.",
    "901380930": "AIR-E S.A.S. E.S.P.",
    "901380949": "CARIBEMAR DE LA COSTA S.A.S. E.S.P. (AFINIA)",
    "891800219": "EMPRESA DE ENERGÍA DE BOYACÁ S.A. E.S.P.",
    "890800128": "CENTRAL HIDROELÉCTRICA DE CALDAS S.A. E.S.P.",
    "891190127": "ELECTRIFICADORA DEL CAQUETÁ S.A. E.S.P.",
    "900366010": "COMPAÑÍA ENERGÉTICA DE OCCIDENTE S.A.S. E.S.P.",
    "818001629": "EMPRESA DISTRIBUIDORA DEL PACÍFICO S.A. E.S.P.",
    "891180001": "ELECTRIFICADORA DEL HUILA S.A. E.S.P.",
    "892002210": "ELECTRIFICADORA DEL META S.A. E.S.P.",
    "891200200": "CENTRALES ELÉCTRICAS DE NARIÑO S.A. E.S.P.",
    "890500514": "CENTRALES ELÉCTRICAS DEL NORTE DE SANTANDER S.A. E.S.P.",
    "800052640": "EMPRESA DE ENERGÍA DEL QUINDÍO S.A. E.S.P.",
    "816002019": "EMPRESA DE ENERGÍA DE PEREIRA S.A. E.S.P.",
    "890201230": "ELECTRIFICADORA DE SANTANDER S.A. E.S.P.",
    "901280981": "CELSIA TOLIMA S.A. E.S.P.",
    "800249860": "CELSIA COLOMBIA S.A. E.S.P.",
    "890399003": "EMPRESAS MUNICIPALES DE CALI E.I.C.E. E.S.P.",
    "836000349": "EMPRESAS MUNICIPALES DE CARTAGO E.S.P.",
    "892099499": "EMPRESA DE ENERGÍA DE ARAUCA E.S.P.",
    "844004576": "EMPRESA DE ENERGÍA DE CASANARE S.A. E.S.P.",
    "846000241": "EMPRESA DE ENERGÍA DEL PUTUMAYO S.A. E.S.P.",
    "846000553": "EMPRESA DE ENERGÍA DEL BAJO PUTUMAYO S.A. E.S.P.",
    "846000060": "EMPRESA DE ENERGÍA DEL VALLE DE SIBUNDOY S.A. E.S.P.",
    "827000108": "SOCIEDAD PRODUCTORA DE ENERGÍA DE SAN ANDRÉS Y PROVIDENCIA S.A. E.S.P.",
    "900339174": "ENERGÍA PARA EL AMAZONAS S.A. E.S.P.",
    "843000057": "EMPRESA DE ENERGÍA DEL GUAINÍA LA CEIBA S.A. E.S.P.",
    "822004680": "EMPRESA DE ENERGÍA ELÉCTRICA DEL DEPARTAMENTO DEL GUAVIARE S.A. E.S.P.",
    "900927463": "EMPRESA DE ENERGÍA ELÉCTRICA DEL VAUPÉS S.A. E.S.P.",
    "842000155": "EMPRESA DE ENERGÍA ELÉCTRICA DEL DEPARTAMENTO DEL VICHADA S.A. E.S.P.",
}
ENERGIA_DEPARTAMENTO = {
    "05": "890904996", "08": "901380930", "11": "860063875", "13": "901380949", "15": "891800219",
    "17": "890800128", "18": "891190127", "19": "900366010", "20": "901380949", "23": "901380949",
    "25": "860063875", "27": "818001629", "41": "891180001", "44": "901380930", "47": "901380930",
    "50": "892002210", "52": "891200200", "54": "890500514", "63": "800052640", "66": "890800128",
    "68": "890201230", "70": "901380949", "73": "901280981", "76": "800249860", "81": "892099499",
    "85": "844004576", "86": "846000241", "88": "827000108",
}
ENERGIA_MUNICIPIO = {
    "66001": "816002019",                                   # Pereira
    "76001": "890399003", "76892": "890399003",             # Cali, Yumbo (EMCALI)
    "19573": "890399003",                                   # Puerto Tejada (EMCALI)
    "76147": "836000349",                                   # Cartago (EMCARTAGO)
    # Bajo Putumayo
    "86568": "846000553", "86320": "846000553", "86865": "846000553", "86757": "846000553", "86569": "846000553",
    # Valle de Sibundoy
    "86749": "846000060", "86219": "846000060", "86760": "846000060", "86755": "846000060",
    # Zonas no interconectadas con prestador conocido
    "91001": "900339174", "91540": "900339174",             # Leticia, Puerto Nariño (ENAM)
    "94001": "843000057", "94343": "843000057",             # Inírida, Barrancominas (EMELCE)
    "95001": "822004680", "95015": "822004680", "95025": "822004680",  # Guaviare (Energuaviare)
    "97001": "900927463",                                   # Mitú (Enervaupés)
    "99001": "842000155", "99773": "842000155", "99624": "842000155",  # Vichada (Electrovichada)
}

# Nombres del conjunto de gas que no coinciden con DIVIPOLA (normalizados) -> código.
GAS_ALIAS = {
    "BOGOTA DISTRITO CAPITAL": "11001", "CALI": "76001", "CUCUTA": "54001", "TOLU VIEJO": "70823",
    "GUICAN": "15332", "PIENDAMO": "19548", "MOMPOS": "13468", "SANTA CRUZ DE LORICA": "23417",
    "SINCE": "70742", "EL DIFICIL": "47058", "PIJINO": "47545", "PURISIMA": "23586",
    "SAN LUIS DE CUBARRAL": "50223", "EL BORDO": "19532", "LA APARTADA Y LA FRONTERA": "23350",
    "CANALETE": "23090",
}

# ---------------------------------------------------------------- ajustes a mano (AAA)
# Dueño de la cabecera en capitales y grandes ciudades: (municipio, servicio) -> fragmento del
# nombre registrado allí en el RUPS. Si no aparece entre los registrados, la herramienta falla.
AJUSTES_AAA = {
    ("66001", "ACUEDUCTO"): "ACUEDUCTO Y ALCANTARILLADO DE PEREIRA",       # Aguas y Aguas de Pereira
    ("66001", "ALCANTARILLADO"): "ACUEDUCTO Y ALCANTARILLADO DE PEREIRA",
    ("08001", "ASEO"): "ACUEDUCTO ALCANTARILLADO Y ASEO DE BARRANQUILLA",  # Triple A
    ("68001", "ASEO"): "EMPRESA DE ASEO DE BUCARAMANGA",                   # EMAB
    ("76001", "ASEO"): "PROMOCALI",
    # Bogotá: el aseo va por Áreas de Servicio Exclusivo (localidades); la cabecera solo la usan
    # las denuncias, que no traen ubicación: la ASE más grande.
    ("11001", "ASEO"): "LIMPIEZA METROPOLITANA",
}
# Inscritos en aseo solo para reciclaje o aprovechamiento: no recogen la basura, así que solo
# ganan una zona si no hay quien la recoja.
APROVECHAMIENTO = re.compile(r"RECICL|APROVECH|RECUPERA|\bECA\b|EKOPLANET|ECOPLANET|TRANSFORMACION DE MAT")

# Áreas de Servicio Exclusivo de aseo de Bogotá (UAESP, bogota.gov.co): NIT -> (nombre, localidades).
# Área Limpia no está en el RUPS de 2021; su NIT sale de un directorio empresarial (sin verificar
# en una fuente oficial, ADR-35).
ASE_BOGOTA = {
    "901145808": ("PROMOAMBIENTAL DISTRITO S.A.S. E.S.P.",
                  ["USAQUEN", "CHAPINERO", "SANTA FE", "SAN CRISTOBAL", "USME", "CANDELARIA", "SUMAPAZ"]),
    "830123461": ("LIMPIEZA METROPOLITANA S.A. E.S.P.",
                  ["TUNJUELITO", "TEUSAQUILLO", "BOSA", "LOS MARTIRES", "ANTONIO NARINO", "PUENTE ARANDA",
                   "RAFAEL URIBE URIBE", "CIUDAD BOLIVAR"]),
    "830048122": ("CIUDAD LIMPIA BOGOTÁ S.A. E.S.P.", ["KENNEDY", "FONTIBON"]),
    "901144843": ("BOGOTÁ LIMPIA S.A.S. E.S.P.", ["ENGATIVA", "BARRIOS UNIDOS"]),
    "901146434": ("ÁREA LIMPIA DISTRITO CAPITAL S.A.S. E.S.P.", ["SUBA"]),
}


def normal(t):
    t = unicodedata.normalize("NFKD", (t or "").upper())
    t = "".join(c for c in t if not unicodedata.combining(c))
    return re.sub(r"[^A-Z0-9]+", " ", t).strip()


def nit_base(nit):
    return re.sub(r"\D", "", (nit or "").split("-")[0])


def descargar(nombre, url):
    CACHE.mkdir(parents=True, exist_ok=True)
    ruta = CACHE / nombre
    if not ruta.exists():
        print(f"-- descargando {url}", file=sys.stderr)
        with urllib.request.urlopen(url, timeout=300) as r:
            ruta.write_bytes(r.read())
    return json.loads(ruta.read_text(encoding="utf-8"))


def divipola():
    sql = (MIGRACIONES / "V3__semilla_divipola.sql").read_text(encoding="utf-8")
    deptos = dict(re.findall(r"\('(\d{2})', '([^']+)'\)", sql))
    munis = {}
    for cod, dep, nom in re.findall(r"\('(\d{5})', '(\d{2})', '((?:[^']|'')+)', '[A-Z_]+'", sql):
        munis[cod] = (dep, nom.replace("''", "'"))
    return deptos, munis


# Nombres del RUPS que no coinciden con DIVIPOLA: (departamento, municipio) normalizados -> código.
ALIAS = {
    ("VALLE DEL CAUCA", "CALI"): "76001", ("NORTE DE SANTANDER", "CUCUTA"): "54001",
    ("SUCRE", "TOLU VIEJO"): "70823", ("CAUCA", "SOTARA"): "19760", ("TOLIMA", "ARMERO GUAYABAL"): "73055",
    ("BOYACA", "GUICAN"): "15332", ("CAUCA", "PIENDAMO"): "19548", ("NARINO", "CUASPUD"): "52224",
    ("BOLIVAR", "MOMPOS"): "13468",
}


def indices(deptos, munis):
    por_dep = {}
    por_nombre = collections.defaultdict(list)
    for cod, (dep, nom) in munis.items():
        clave = normal(nom)
        por_dep[(normal(deptos[dep]), clave)] = cod
        por_nombre[clave].append(cod)
    return por_dep, por_nombre


def variantes(nombre):
    """Formas comunes de escribir un municipio (sin artículos, sin sufijos entre paréntesis)."""
    n = normal(nombre)
    vs = {n, re.sub(r"\s*\(.*$", "", n)}
    for v in list(vs):
        vs.add(re.sub(r"^(SAN|SANTA|EL|LA|LOS|LAS) ", r"\1 ", v))
        vs.add(v.replace(" D C", "").strip())
        vs.add(re.sub(r" DE INDIAS$| DISTRITO.*$", "", v))
    return [v for v in vs if v]


def main():
    deptos, munis = divipola()
    por_dep, por_nombre = indices(deptos, munis)
    rups = descargar("rups.json", RUPS_URL)

    def municipio(dep, mun):
        d = normal(dep)
        if (d, normal(mun)) in ALIAS:
            return ALIAS[(d, normal(mun))]
        for v in variantes(mun):
            if (d, v) in por_dep:
                return por_dep[(d, v)]
            cands = [c for c in por_nombre.get(v, []) if normal(deptos[munis[c][0]]).startswith(d[:5])]
            if len(cands) == 1:
                return cands[0]
        return None

    sin_cruce = collections.Counter()
    registros = collections.defaultdict(list)      # (municipio, servicio) -> filas
    municipios_por_nit = collections.defaultdict(set)
    nombres = collections.defaultdict(collections.Counter)
    for x in rups:
        if x.get("estado") != "OPERATIVA" or x.get("servicio") not in SERVICIOS_RUPS:
            continue
        cod = municipio(x.get("departamento_prestacion"), x.get("municipio_prestacion"))
        nit = nit_base(x.get("nit"))
        if not cod or not nit:
            sin_cruce[(x.get("departamento_prestacion"), x.get("municipio_prestacion"))] += 1
            continue
        registros[(cod, SERVICIOS_RUPS[x["servicio"]])].append(x)
        municipios_por_nit[nit].add(cod)
        nombres[nit][x["nombre"].strip()] += 1
    print(f"-- RUPS: {sum(len(v) for v in registros.values())} registros cruzados, "
          f"{sum(sin_cruce.values())} sin municipio", file=sys.stderr)

    empresas = {}                                   # nit -> nombre
    fuentes = {}                                    # (zona, servicio) -> (nit, fuente)

    def nombre_rups(nit):
        return nombres[nit].most_common(1)[0][0]

    def puntaje(x):
        nit = nit_base(x["nit"])
        recolecta = not (x.get("servicio") == "ASEO" and APROVECHAMIENTO.search(normal(x["nombre"])))
        return (recolecta, TIPO.get(x.get("tipo_prestador"), 1), TAMANO.get(x.get("clasificacion"), 1),
                len(municipios_por_nit[nit]), -int(nit))

    # ------------------------------------------------ acueducto, alcantarillado y aseo
    for (cod, servicio), filas in sorted(registros.items()):
        por_nit = {}
        for x in filas:
            n = nit_base(x["nit"])
            if n not in por_nit or puntaje(x) > puntaje(por_nit[n]):
                por_nit[n] = x
        orden = sorted(por_nit.values(), key=puntaje, reverse=True)
        urbano = orden[0]
        fuente_u = "RUPS (Superservicios): prestador más grande registrado en el municipio"
        ajuste = AJUSTES_AAA.get((cod, servicio))
        if ajuste:
            elegidos = [x for x in orden if ajuste in normal(x["nombre"])]
            if not elegidos:
                sys.exit(f"Ajuste {cod} {servicio} '{ajuste}' no está entre: {[x['nombre'] for x in orden]}")
            urbano = elegidos[0]
            fuente_u = "RUPS (Superservicios), ajustado a mano: prestador principal de la ciudad"
        resto = [x for x in orden if nit_base(x["nit"]) != nit_base(urbano["nit"])]
        rural = resto[0] if resto else urbano
        fuente_r = ("RUPS (Superservicios): el más grande de los demás prestadores del municipio" if resto
                    else "RUPS (Superservicios): único prestador registrado en el municipio")
        for zona, x, f in ((f"{cod}-U", urbano, fuente_u), (f"{cod}-R", rural, fuente_r)):
            n = nit_base(x["nit"])
            empresas[n] = nombre_rups(n)
            fuentes[(zona, servicio)] = (n, f)

    # Aseo de Bogotá por Áreas de Servicio Exclusivo (UAESP).
    comunas = leer_comunas()
    for n, (nom, localidades) in ASE_BOGOTA.items():
        empresas[n] = nom
        for loc in localidades:
            claves = [c for c, nombre in comunas.items() if c.startswith("11001-L") and normal(nombre).endswith(loc)]
            if len(claves) != 1:
                sys.exit(f"Localidad '{loc}' de Bogotá: {claves}")
            fuentes[(claves[0], "ASEO")] = (n, "UAESP: Área de Servicio Exclusivo de aseo de Bogotá")
    if len([z for (z, s) in fuentes if z.startswith("11001-L") and s == "ASEO"]) != 20:
        sys.exit("Las Áreas de Servicio Exclusivo no cubren las 20 localidades de Bogotá")

    # ------------------------------------------------ gas natural
    gas = descargar("gas-mercados.json", GAS_URL)
    municipios_gas = collections.defaultdict(set)    # nit -> municipios
    nombre_gas = {}
    sin_gas = collections.Counter()
    mercados = []
    for m in gas:
        if int(m.get("ultimo") or 0) < 2022:
            continue
        nit = nit_base(m["nit"])[:9]
        nombre_gas[nit] = m["empresa"].strip()
        nombres_m = [re.sub(r"\d+$|\s+URBANO$", "", normal(s)).strip() for s in m["municipios"].split(" - ")]
        mercados.append((nit, [s for s in nombres_m if s]))

    def candidatos(s):
        return [GAS_ALIAS[s]] if s in GAS_ALIAS else por_nombre.get(s, [])

    # Departamentos de cada empresa, según los municipios sin ambigüedad de todos sus mercados.
    deps_empresa = collections.defaultdict(collections.Counter)
    for nit, nombres_m in mercados:
        for s in nombres_m:
            if len(candidatos(s)) == 1:
                deps_empresa[nit][munis[candidatos(s)[0]][0]] += 1
    for nit, nombres_m in mercados:
        deps_mercado = collections.Counter(munis[candidatos(s)[0]][0] for s in nombres_m if len(candidatos(s)) == 1)
        preferidos = [d for d, _ in deps_mercado.most_common()] + [d for d, _ in deps_empresa[nit].most_common()]
        for s in nombres_m:
            cands = candidatos(s)
            for d in preferidos:
                if len(cands) <= 1:
                    break
                if any(munis[c][0] == d for c in cands):
                    cands = [c for c in cands if munis[c][0] == d]
            if len(cands) == 1:
                municipios_gas[nit].add(cands[0])
            else:
                sin_gas[s] += 1
    print(f"-- gas: {len(municipios_gas)} empresas; sin cruce: {dict(sin_gas)}", file=sys.stderr)
    dueno_gas = {}
    for nit, ms in municipios_gas.items():
        for cod in ms:
            actual = dueno_gas.get(cod)
            if actual is None or (len(ms), -int(nit)) > (len(municipios_gas[actual]), -int(actual)):
                dueno_gas[cod] = nit
    for cod, nit in dueno_gas.items():
        empresas[nit] = nombre_gas[nit]
        for zona in (f"{cod}-U", f"{cod}-R"):
            fuentes[(zona, "GAS")] = (nit, "Superservicios, tarifas aplicadas de gas natural (mercados 2022-2023)")

    # ------------------------------------------------ energía
    for cod, (dep, _) in munis.items():
        nit = ENERGIA_MUNICIPIO.get(cod) or ENERGIA_DEPARTAMENTO.get(dep)
        if not nit:
            continue
        empresas[nit] = EMPRESAS_ENERGIA[nit]
        f = ("Operador de red del municipio (tabla a mano, ADR-35)" if cod in ENERGIA_MUNICIPIO
             else "Operador de red del departamento (tabla a mano, ADR-35)")
        for zona in (f"{cod}-U", f"{cod}-R"):
            fuentes[(zona, "ENERGIA")] = (nit, f)

    imprimir(empresas, fuentes, comunas)
    resumen = collections.Counter(s for (_, s) in fuentes)
    print(f"-- {len(empresas)} empresas, coberturas por servicio: {dict(resumen)}", file=sys.stderr)


def leer_comunas():
    import gzip
    ruta = AQUI.parent / "src/main/resources/db/datos/zonas-comunas.tsv.gz"
    with gzip.open(ruta, "rt", encoding="utf-8") as f:
        return {linea.split("\t")[0]: linea.split("\t")[1] for linea in f}


def texto(v):
    return "'" + str(v).replace("'", "''") + "'"


def imprimir(empresas, fuentes, comunas):
    print("-- =====================================================================")
    print("-- Empresas prestadoras REALES y su zona de cobertura (ADR-35): una empresa por zona y")
    print("-- servicio. Generado por herramientas/generar-prestadores.py. No editar a mano.")
    print("-- Fuentes: RUPS de la Superservicios (datos.gov.co 4qkq-csdn), tarifas de gas natural")
    print("-- (ek3f-5wn4), Áreas de Servicio Exclusivo de aseo de Bogotá (UAESP) y la tabla de")
    print("-- operadores de red de energía de la herramienta.")
    print("-- =====================================================================\n")
    print("INSERT INTO catalogo.empresas (id, nit, nombre) VALUES")
    print(",\n".join(f"  ({texto(uuid.uuid5(ESPACIO, n))}, {texto(n)}, {texto(nom)})"
                     for n, nom in sorted(empresas.items())) + ";\n")
    print("-- Solo las zonas que existen (V20 crea la cabecera U solo donde el DANE la delimita).")
    print("INSERT INTO catalogo.coberturas_zona (id_zona, tipo_servicio, id_empresa, fuente)")
    print("SELECT v.zona, v.servicio, v.empresa::uuid, v.fuente")
    print("FROM (VALUES")
    print(",\n".join(f"  ({texto(z)}, {texto(s)}, {texto(uuid.uuid5(ESPACIO, n))}, {texto(f)})"
                     for (z, s), (n, f) in sorted(fuentes.items())))
    print(") AS v (zona, servicio, empresa, fuente)")
    print("JOIN catalogo.zonas z ON z.id = v.zona;")


if __name__ == "__main__":
    main()
