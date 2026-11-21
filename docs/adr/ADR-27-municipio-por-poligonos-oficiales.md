# ADR-27: Municipio del reporte por polígonos oficiales (PostGIS)

## Estado
Aceptada. Reemplaza el método de ubicación de ADR-08 (centroide más cercano), que queda
solo como respaldo.

## Contexto
Persistencia asignaba a cada reporte el municipio cuyo **centroide** DIVIPOLA estuviera
más cerca. Es simple y no necesita PostGIS, pero los municipios no son círculos:

- **Cerca de un límite:** un punto queda más cerca del centroide del vecino.
- **En municipios grandes o alargados:** el centroide está lejos de buena parte del
  territorio.

Medido contra los polígonos oficiales del DANE, el centroide se equivocaba en el **44 %** de
puntos al azar del territorio y en el **60 %** de puntos a menos de 500 m de un límite.
Hay casos reales en Bogotá:
- un punto de Bosa quedaba en Soacha;
- uno del norte quedaba en Chía.

Esos porcentajes exageran el efecto real, porque los reportes se concentran en cascos
urbanos cerca del centroide, pero el error existe. Afecta:
- el enrutamiento del caso a la empresa con cobertura;
- los indicadores por municipio;
- las exportaciones a la SSPD.

## Decisión
- **Datos:** el Marco Geoestadístico Nacional 2024 del DANE, capa `MGN_ADM_MPIO_GRAFICO`
  (WGS84). Tiene 1121 polígonos: 1102 municipios, 18 áreas no municipalizadas y 1 isla.
  Todos sus códigos existen en el catálogo.
- **Simplificación:** `ST_CoverageSimplify` a 0,0001° (~11 m). Los bordes compartidos se
  simplifican igual en ambos lados, así que no quedan huecos ni solapes entre vecinos.
  - **Precisión:** 0 % de error en puntos al azar y 0,075 % a menos de 500 m de un límite.
    Se midió contra los polígonos originales, con 20 000 puntos de cada tipo y después de
    la ida y vuelta por TWKB.
  - **Tamaño:** de 5,9 a 2,2 millones de vértices, es decir 4,7 MB comprimidos en el
    repositorio y 35 MB en la base.
- **Herramienta:** `servicios/persistencia/herramientas/generar-limites-municipales.sh`
  reproduce todo el proceso: descarga, carga, simplificación y exportación a
  `db/datos/limites-municipales-mgn2024.bin.gz`.
- **Migración V13 (Java, porque el archivo es binario):**
  1. **Ubica PostGIS:** usa el esquema donde ya esté (en Supabase, `extensions`). Si no está,
     lo instala en `extensions` si ese esquema existe, o si no en `public`.
  2. **Crea la tabla** `catalogo.limites_municipios` (código, `geometry(MultiPolygon,4326)`,
     fuente) con índice GIST, y carga los polígonos reparándolos con `ST_MakeValid`.
     Después del redondeo a 1 m, uno de los 1121 quedaba con una autointersección.
  3. **Crea la función `catalogo.municipio_en(lat, lon)`**, con su propio `search_path`:
     devuelve el polígono que contiene el punto (`ST_Covers`) o, si ninguno lo contiene
     (en el mar cerca de la costa), el más cercano (KNN sobre el índice).
  - Todo va calificado con el esquema de PostGIS, así que no depende del `search_path` de
    la conexión.
- **Consulta:** `CatalogoTerritorialJdbc.municipioEn` usa esa función. Si no hubiera
  polígonos, vuelve al centroide. El **contrato no cambia**: la misma operación
  `/interno/v1/catalogo/municipio-cercano` y la misma respuesta.
- **Entorno local y pruebas:** `infra/postgres/Dockerfile` construye PostgreSQL 17 con
  PostGIS 3 desde la imagen oficial y el paquete de PGDG. La imagen oficial
  `postgis/postgis` solo existe para amd64; esta funciona en amd64 y arm64. Las pruebas de
  integración la usan mediante `BaseDePrueba`.

## Alternativas descartadas
- **Polígonos sin simplificar:** 95 MB y 5,9 millones de vértices, para ganar 0,075 % en
  los bordes. Pesa demasiado en el repositorio y en la base gratuita de Supabase.
- **Simplificar cada polígono por separado (`ST_SimplifyPreserveTopology`):** abre huecos y
  solapes entre vecinos, donde un punto quedaría sin municipio o en dos.
- **Tolerancias mayores (0,0005° o 0,001°):** ahorran 2 a 4 MB, pero el error en los bordes
  sube a 0,5 % y 1 %, y algunos puntos quedan sin polígono.
- **Resolver en la app o en Reportes:** el catálogo vive en Persistencia (ADR-02).

## Consecuencias
- **Costo:** cada consulta tarda entre 0,04 y 0,15 ms. La creación de reportes no cambia
  de forma medible.
- **Supabase:** la primera vez que arranque Persistencia con V13 se instala o reutiliza
  PostGIS y se cargan 35 MB, muy por debajo del límite gratuito de 500 MB. El rol que usa
  Persistencia debe poder crear la extensión. Si no puede, se habilita PostGIS desde el
  panel de Supabase y se vuelve a arrancar.
- **Volumen local existente:** se creó con la imagen Alpine. La imagen nueva es Debian,
  con otra biblioteca de C, así que hay que reconstruir los índices de texto una vez con
  `REINDEX DATABASE servired`.
- **Reportes ya guardados:** conservan el municipio que les dio el centroide; la migración
  no los recalcula. Hacerlo cambiaría reportes, casos (incluida la empresa asignada), la
  vista de Indicadores y lo ya exportado, así que se decide aparte. En la base local
  cambiarían 12 de 2755.
  **Hecho en [ADR-32](ADR-32-recalculo-del-municipio-de-reportes-viejos.md):** la migración V17 los
  recalcula, deja registro de cada cambio y solo reenruta los casos intactos.
- **Límites que cambian:** al publicar el DANE un MGN nuevo, se regenera el archivo con la
  herramienta y una migración nueva reemplaza los polígonos.
- **Municipio sin polígono:** Nuevo Belén de Bajirá (27493) está en el catálogo pero no en
  el MGN 2024, así que sus puntos quedan en el municipio cuyo polígono los contiene.
