# ADR-15 · Indicadores, mapas y datos abiertos anonimizados

**Estado:** aceptada · **Fecha:** 2026-11-17 · **Requisitos:** RF-8, RF-9, RF-10, RNF-DAT2, RNF-DAT4

## Contexto
RF-8 a RF-10 piden mapas de eventos activos, resueltos y agregados territoriales,
indicadores de frecuencia, duración, severidad y distribución, exportación
regulatoria y conjuntos abiertos anonimizados. RNF-DAT2 exige eliminar o agregar
lo que identifique personas o ubicaciones sensibles, y RNF-DAT4 que cada
indicador registre definición, fuente, periodo, cobertura y fecha de
actualización. El SAD no fija umbrales ni el nivel de agregación.

## Decisión
- **Vista de lectura:** Indicadores proyecta los eventos (`reporte.creado`,
  `restablecimiento.registrado`, `validacion.registrada` del corte y `caso.*`) en
  una fila por reporte (`indicadores.vista_reportes`) mediante Persistencia. Sin
  ciudadano ni descripción. La proyección tolera eventos en desorden: los datos
  de creación y de restablecimiento se completan una vez; la validación y el
  estado del caso solo se reemplazan por datos más recientes. No consume
  denuncias (ADR-09). Los agregados se calculan sobre la vista con índices.
- **Indicadores (RF-9):** frecuencia (reportes); duración (promedio, mediana y
  p90 de `restablecimiento − inicioEstimado`); severidad (índice 1–4 = promedio
  de la magnitud, distribución por magnitud y % validado por la comunidad);
  atención (% restablecidos o resueltos y tiempo medio registro → resolución);
  distribución por departamento, municipio y celda.
- **Anonimizador (RNF-DAT2), para todo lo público:** solo agregados; un grupo
  con **menos de 3 reportes se suprime** (k-anonimato, k = 3) y la duración solo
  se publica con al menos 3 valores; el mapa público usa **celdas de 0,01°
  (~1,1 km)** y oculta las celdas con menos de 3 reportes; nunca se publican ids,
  descripciones ni puntos.
- **Metadatos (RNF-DAT4):** toda respuesta pública lleva las definiciones, la
  fuente, el periodo, los filtros, la cobertura de la vista y la fecha de su
  última actualización. Periodo por defecto: últimos 30 días; máximo 366.
- **Datos abiertos (RF-10):** conjunto mensual municipio × servicio en CSV y
  JSON, con las filas pequeñas marcadas `suprimido` y sin coordenadas.
- **Personal autenticado:** mapa operativo con puntos (operador: solo su
  empresa; regulador y administrador: todo) y exportación regulatoria CSV (solo
  regulador y administrador), sin ciudadano, con coordenadas redondeadas a 3
  decimales (~110 m). El CSV neutraliza fórmulas de hoja de cálculo.

## Consecuencias
- Un municipio con pocos reportes aparece como suprimido: se protege a sus
  habitantes a costa de detalle; la exportación regulatoria sí lo incluye.
- La celda de ~1,1 km puede revelar la zona en áreas rurales muy despobladas; se
  mitiga con k = 3 y puede ampliarse sin cambiar contratos.
  **Resuelto en [ADR-28](ADR-28-privacidad-rural-del-mapa.md):** fuera de las cabeceras
  municipales las celdas miden 0,05° (~5,5 km) y se muestran desde 5 reportes.
- No hay tasas por habitante (no hay datos de población en la semilla).
- Las exportaciones se registran en el log; el registro de auditoría formal
  (RF-12) llega en la fase 7.
- Los eventos acumulados en `indicadores.eventos` desde la fase 2 se procesaron
  al arrancar el consumidor, así que la vista incluye la historia.
