# ADR-35: Empresas prestadoras reales y una sola dueña por zona de servicio

## Estado
Aceptada. Reemplaza las empresas de ejemplo (V4) y la regla de enrutamiento de ADR-08 y
ADR-14: antes iba a la empresa con cobertura de menor identificador; ahora va a la única
empresa dueña de la zona.

## Contexto
El catálogo tenía 5 empresas ficticias con cobertura en 6 ciudades. Varias podían cubrir el
mismo municipio y servicio, y un administrador podía enrutar un caso a cualquiera.

Se pidió:
- cargar las empresas reales de Colombia;
- darle a cada una una zona de cobertura;
- que **solo** la empresa de una zona gestione los reportes de esa zona.

Las fuentes abiertas no traen una sola empresa por zona:
- **RUPS de la Superservicios** ([datos.gov.co 4qkq-csdn](https://www.datos.gov.co/Hacienda-y-Cr-dito-P-blico/Registro-nico-de-Prestadores-de-Servicios-P-blicos/4qkq-csdn)):
  - 9 327 prestadores operativos; los datos se actualizaron por última vez en 2021;
  - solo acueducto, alcantarillado y aseo traen el municipio donde prestan;
  - en un mismo municipio suele haber varios (Bogotá: 35 de acueducto y 335 de aseo);
  - no dice qué vereda atiende cada acueducto veredal.
- **Energía:** no trae zonas. Además, la lista mezcla generadoras con distribuidoras.
- **Gas natural:** el RUPS tampoco trae zonas. El conjunto de tarifas aplicadas de la
  Superservicios ([ek3f-5wn4](https://www.datos.gov.co/resource/ek3f-5wn4.json)) sí lista
  los municipios de cada mercado.

## Decisión
Se tomaron con el usuario estas decisiones:
- se cargan solo las empresas que quedan con zona;
- se usan zonas más finas que el municipio;
- energía y gas salen de una tabla armada a mano;
- el administrador solo puede enrutar a la dueña.

### Zonas (V20, `catalogo.zonas`)
Cada punto cae en la zona más específica:
1. **Localidad o comuna** (`<municipio>-L..`/`-C..`), con los polígonos oficiales de:
   - [Bogotá](https://datosabiertos.bogota.gov.co/dataset/localidad-bogota-d-c): 20 localidades, Catastro/IDECA, CC BY 4.0;
   - Medellín: 16 comunas y 5 corregimientos, GeoMedellín;
   - Cali: 22 comunas, IDESC;
   - Barranquilla: 5 localidades, unión de sus barrios del POT, CC BY-SA 4.0.

   Cartagena y Bucaramanga no publican polígonos abiertos de sus localidades o comunas.
2. **Cabecera urbana** (`-U`): el área urbana del DANE (MGN 2024), ya cargada para ADR-28.
3. **Resto rural** (`-R`).

Hay 2 293 zonas. Una localidad solo tiene dueño propio cuando difiere del de su ciudad; si no,
hereda el de la cabecera o el resto rural que contiene el punto.

### Dueños (V21, `catalogo.coberturas_zona`)
La llave primaria (zona, servicio) garantiza una sola empresa.
- **Acueducto, alcantarillado y aseo (RUPS):**
  - **Cabecera:** gana el prestador más grande. Se ordena por tipo de prestador (empresa antes
    que municipio, municipio antes que organización comunitaria), luego por su clasificación
    por número de usuarios, luego por en cuántos municipios presta y al final por el NIT.
  - **Aseo:** los inscritos solo para reciclaje o aprovechamiento van al final, porque no
    recogen la basura.
  - **Ajustes a mano:** en las ciudades donde la regla se equivocaba, siempre entre los
    registrados allí: Pereira (Aguas y Aguas), Barranquilla (Triple A en aseo), Bucaramanga
    (EMAB), Cali (Promocali) y la cabecera de Bogotá en aseo.
  - **Rural:** gana el más grande de los **demás** prestadores del municipio; si no hay otro,
    el mismo de la cabecera.
- **Aseo de Bogotá:** por las 5 Áreas de Servicio Exclusivo de la UAESP, por localidad
  ([bogota.gov.co](https://bogota.gov.co/mi-ciudad/habitat/como-esta-dividido-el-esquema-de-aseo-en-las-localidades-de-bogota)):
  - Promoambiental Distrito;
  - LIME;
  - Ciudad Limpia;
  - Bogotá Limpia;
  - Área Limpia: no está en el RUPS de 2021. Su NIT (901146434) sale de un directorio
    empresarial y **no está verificado** en una fuente oficial.
- **Gas natural:** los mercados con tarifas de 2022 o después. Si dos empresas comparten un
  municipio, gana la que atiende más municipios.
- **Energía (tabla a mano):** el operador de red de cada departamento, con estas excepciones:
  - Pereira: Energía de Pereira;
  - Cali, Yumbo y Puerto Tejada: EMCALI, según su
    [informe de inversiones 2024](https://www.emcali.com.co/documents/d/energia/20250410-informe-ejecucion-de-planes-de-inversion-2024_emcali);
  - Cartago: EMCARTAGO;
  - Bajo Putumayo y Valle de Sibundoy: sus empresas;
  - zonas no interconectadas con prestador conocido: ENAM en Leticia, EMELCE en Inírida,
    Energuaviare, Enervaupés en Mitú y Electrovichada.
  - **Enel Colombia:** se usa el NIT de Emgesa, que lo conservó al absorber a Codensa en 2022.
  - **Aproximaciones:**
    - todo el Magdalena queda con Air-e, aunque Afinia atiende algunos municipios del sur;
    - los municipios del Pacífico de Nariño y Cauca quedan con el operador del departamento.
- **Zonas sin prestador** (por ejemplo, gas en Leticia): la zona queda sin dueño y el caso,
  sin empresa.
- **Resultado:** 1 803 empresas y 10 159 coberturas. El identificador de cada empresa es el
  UUID v5 de su NIT, así que no cambia al regenerar.
- **Reproducible:** `herramientas/generar-prestadores.py` (V21) y
  `herramientas/generar-zonas-comunas.py` (`db/datos/zonas-comunas.tsv.gz`).

### Enrutamiento y permisos
- **Persistencia:** `/interno/v1/catalogo/cobertura` resuelve la zona y su dueña con las
  funciones `catalogo.empresa_en`, `zona_en` y `empresa_de_municipio`:
  - por el punto del reporte;
  - para denuncias, por la ubicación guardada, porque su evento no la trae (RNF-SEG4);
  - si no hay nada de eso, por la cabecera del municipio.
- **Atención:** abre el caso y la moderación en esa empresa.
- **Administrador:** `PUT /casos/{id}/empresa` solo acepta a la dueña de la zona del caso. Si
  no, responde 409 con `FUERA_DE_ZONA` o `SIN_EMPRESA_EN_ZONA`.
- **Portal:** muestra la zona y ofrece un solo botón, «Enrutar a <dueña>», con
  `GET /casos/{id}/zona`.
- **Catálogo:** `GET /api/v1/empresas` sirve las empresas al personal. El portal las usa para
  mostrar nombres y para buscar por nombre o NIT al crear un operador.

### Datos existentes (V22)
- **Casos:** todos, también los cerrados, pasan a la dueña de su zona. Si cambian de empresa,
  se desasigna el operador, sube la versión y queda una entrada en la historia.
- **Moderaciones:** pasan a la dueña de la zona de la denuncia.
- **Vista de Indicadores:** toma la empresa del caso.
- **Operadores de las empresas de ejemplo:** quedan desactivados, sin empresa y con sus
  sesiones revocadas. La restricción `operador_con_empresa` ahora admite un operador
  **inactivo** sin empresa.
- **Registro:** cada cambio queda en `catalogo.reasignacion_empresas`, de solo inserción.
- **Limpieza:** se borran las empresas de ejemplo. La cobertura por municipio pasa a ser una
  vista (`catalogo.coberturas`) que usa `reportes.recalcular_municipios` (V17).
- **Medido en la base local:** 2 874 casos, 223 moderaciones y 122 cuentas, en 7 s.

## Alternativas descartadas
- **Cargar las 9 327 empresas del RUPS:** la mayoría nunca recibiría un caso, porque otra es
  la dueña de su zona.
- **Varias empresas por zona con desempate:** contradice que **solo** una gestione la zona.
- **Zonas solo por municipio:** en Bogotá el aseo tiene un operador por localidad.
- **Sacar energía y gas de los reportes web del SUI:** son consultas, no descargas.

## Consecuencias
- **Datos de 2021:** puede haber empresas liquidadas o fusionadas. Se corrige con la
  herramienta y una migración nueva.
- **Zona rural:** su dueña es una aproximación, porque no se sabe qué vereda atiende cada
  acueducto. Un administrador ya no puede corregirlo caso a caso, sino solo cambiando la
  cobertura.
- **Operadores de ejemplo:** el administrador debe crear cuentas nuevas en las empresas reales.
- **Pruebas:** usan las empresas reales; Enel es la de energía en Bogotá y Cundinamarca.
