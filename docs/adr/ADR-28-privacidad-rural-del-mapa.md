# ADR-28: Privacidad rural del mapa público: celdas y k distintos fuera de las cabeceras

## Estado
Aceptada. Ajusta la política de anonimización de ADR-15 (RNF-DAT2) para el mapa público.

## Contexto
El mapa público agrupa los reportes en celdas de 0,01° (~1,1 km) y solo muestra las que
tienen al menos 3 reportes (k = 3). En una ciudad, una celda de 1 km tiene miles de
viviendas, y 3 reportes no señalan a nadie. En el campo es distinto:

- **Las cabeceras municipales** ocupan unos **4160 km²**, el 0,4 % del territorio (medido
  con el MGN 2024).
- **El resto de la población**, alrededor de una cuarta parte según el CNPV 2018, vive en
  centros poblados y rural disperso.
- **Densidad rural:** en promedio, del orden de **10 habitantes por km²**. Es un promedio
  nacional y varía mucho.

Una celda rural de ~1,2 km² puede tener entonces una docena de personas, tres o cuatro
viviendas. Con k = 3, una celda visible puede ser, en la práctica, «estas tres casas
reportaron». Los vecinos o la empresa sabrían quién reportó. ADR-15 ya lo había anotado
como riesgo.

## Decisión
- **Qué es urbano:** las áreas urbanas de las **cabeceras municipales** del DANE (MGN 2024,
  capa `MGN_URB_ZONA_URBANA`, clase 1): 1103 polígonos, uno por municipio. Los centros
  poblados (clase 2) cuentan como rurales, igual que en el DANE.
  - **Tamaño:** simplificado a 0,00005° (~5 m), con 0,3 % de diferencia de área; ocupa 411 KB.
  - **Reproducible:** `herramientas/generar-cabeceras-municipales.sh`.
- **Política del mapa público:**

  | Zona | Celda | Se muestra desde |
  |---|---|---|
  | Cabecera municipal | 0,01° (~1,1 km) | 3 reportes |
  | Fuera de ella (centros poblados y rural disperso) | **0,05° (~5,5 km)** | **5 reportes** |

  - **Celda rural:** cubre unos 30 km² en vez de ~1,2 km², del orden de 300 personas en
    vez de una docena.
  - **k = 5:** evita que una celda grande pero casi vacía vuelva a señalar a unas pocas
    viviendas.
  - **Las dos medidas a la vez:** agrandar solo la celda deja k = 3 en zonas muy vacías,
    y subir solo k deja la ubicación a 1 km.
- **Clasificación guardada:** la vista de Indicadores guarda `en_cabecera` por reporte.
  Persistencia la calcula al proyectar el reporte (`catalogo.en_cabecera`) y la migración
  V14 la completó para los existentes. Así no se evalúan polígonos en cada consulta del mapa.
  - Un reporte **sin clasificar** se trata como **rural**, lo más protector.
  - La reconstrucción de R14 también compara esta columna.
- **Cálculo:**
  - **Persistencia** agrupa cada reporte en la cuadrícula que le corresponde
    (`celdaRuralGrados`; por defecto 0,05°, nunca menor que la urbana).
  - **Indicadores** aplica el k de cada zona.
  - Si una celda rural y una urbana comparten centro, se publican como una sola, después
    de aplicar el umbral de cada una.
- **Contrato, solo con campos nuevos:**
  - cada celda trae su `celdaGrados` (0,01 o 0,05);
  - el catálogo publica `umbralSupresionRural` y `celdaMapaRuralGrados`.

  Las apps y el portal dibujan un círculo en el centro de la celda, así que no necesitan
  cambios.

## Alternativas descartadas
- **Usar el municipio rural como unidad:** la cuadrícula ya da celdas de tamaño parejo, y
  los municipios rurales grandes (miles de km²) perderían toda utilidad para ubicar cortes.
- **Clasificar por distancia al centroide del municipio:** los cascos urbanos tienen formas
  y tamaños muy distintos. El polígono oficial es exacto y pesa 411 KB.
- **Privacidad diferencial (ruido en los conteos):** protege mejor, pero da conteos
  inexactos en un mapa operativo para la ciudadanía y complica explicarlo. Se puede
  reconsiderar para los datos abiertos.
- **Un tamaño de celda según la densidad de población:** requiere población por celda, que
  no está en la semilla. Cabecera o rural es una aproximación oficial y suficiente.

## Consecuencias
- **Cortes rurales menos visibles:** en el campo, un corte aparece con 5 reportes en vez de
  3 y en una zona de ~5,5 km. El personal sigue viendo los puntos exactos en el mapa
  operativo.
- **Lo que no cambia:** el resumen, los indicadores territoriales y los datos abiertos
  (municipio × servicio × mes) mantienen k = 3, porque agrupan municipios completos y no
  ubican a nadie dentro de ellos.
- **Datos que cambian:** cuando el DANE publique un MGN nuevo, se regeneran las cabeceras
  y una migración nueva las reemplaza. Al recalcular `en_cabecera` se reconstruye la vista.
- **Pruebas:**
  - Persistencia: la clasificación de un punto en la cabecera de Bogotá, uno en Sumapaz y
    uno en un centro poblado de Soacha, y las celdas de 0,01° y 0,05°.
  - Indicadores: el k por zona, una celda sin clasificar tratada como rural y las celdas
    con el mismo centro.
  - e2e `privacidad-rural.py`: con 4 reportes rurales la zona no aparece y con 5 aparece
    como una celda de 0,05°; 3 urbanos aparecen como una de 0,01°; la vista queda alineada.
