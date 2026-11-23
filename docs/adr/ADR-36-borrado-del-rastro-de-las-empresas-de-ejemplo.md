# ADR-36: Borrado de todo lo relacionado con las empresas de ejemplo

## Estado
Aceptada. Completa [ADR-35](ADR-35-empresas-reales-y-zonas-de-servicio.md).

## Contexto
V22 reemplazó las 5 empresas de ejemplo por las reales y reasignó lo existente, pero conservó
el rastro: los operadores de esas empresas (desactivados), sus sesiones, lo que escribieron en
la historia de los casos y el registro de la reasignación.

El usuario pidió borrar de la base **todo** registro relacionado con las empresas de ejemplo,
incluidos los casos y reportes que fueron suyos, el registro de la reasignación, los eventos y
la auditoría.

En la base local eso era:
- 122 operadores;
- 2 789 de los 2 896 casos;
- 223 moderaciones;
- 3 134 filas del registro de la reasignación;
- 1 703 eventos del outbox;
- 34 registros de auditoría.

## Decisión
- **Cómo se borra:** V23 crea `infraestructura.borrar_rastro_empresas_ejemplo()` y la llama una
  vez. La función solo toca las 5 empresas de ejemplo de V4 y lo que se derivó de ellas; si se
  corre de nuevo, no borra nada más. Es **irreversible** y se aplica en cada base, también en
  Supabase.
- **Qué se borra:**
  - **Operadores de ejemplo**, con sus sesiones, las entradas de historia que escribieron
    (también en casos ajenos) y las moderaciones que decidieron.
  - **Casos y moderaciones que fueron de esas empresas**, con su reporte o denuncia, la historia
    de ambos, las fotos (las filas), los votos, el registro de recálculo de municipio y la fila
    en la vista de Indicadores.
  - **Eventos del outbox y respuestas de idempotencia** que nombran cualquiera de esos registros
    o empresas. Se buscan los UUID dentro del JSON.
  - **Filas del registro de la reasignación (V22)** que nombran esas empresas o esos operadores.
  - **Registros de auditoría** que nombran esas empresas o cuyo actor fue uno de esos
    operadores. Los registros que solo se refieren a un reporte o caso borrado se conservan.
- **Lo que se conserva:** no se registra lo borrado, ni siquiera sus identificadores; solo los
  conteos que devuelve la función.
- **Tablas de solo inserción** (auditoría, reasignación, recálculo): sus disparadores se
  desactivan solo dentro de la función.
- **Reencadenamiento:** se recalculan la huella y el enlace de todos los registros desde el
  primero borrado. La verificación vuelve a dar «íntegra» y el archivado (ADR-29) sigue
  funcionando.
  - **Constancia:** queda en `auditoria.reencadenamientos`, de solo inserción, con la secuencia
    desde la que se recalculó, los registros borrados, el motivo y la migración.
  - **Anclas:** las ya publicadas en GitHub con secuencia igual o mayor dejan de coincidir. La
    verificación de anclas (ADR-31) ahora las informa aparte, como **reemplazadas**, y no como
    distintas, si un reencadenamiento del mismo día o posterior empieza en o antes de su
    secuencia. Las anclas siguientes se fijan normalmente.
- **Restricción `operador_con_empresa`:** vuelve a ser la original (operador si y solo si tiene
  empresa), porque ya no quedan operadores inactivos sin empresa.

## Alternativas descartadas
- **Borrar solo los operadores y su rastro:** fue la opción recomendada; el usuario eligió
  borrarlo todo.
- **Dejar la cadena de auditoría rota:** el archivado y las anclas se detendrían para siempre.
- **No tocar la auditoría:** quedarían 34 registros que nombran las empresas de ejemplo.

## Consecuencias
- **Datos perdidos:** se pierde casi todo el historial operativo anterior a ADR-35. Los
  indicadores y mapas cambian.
- **Fotos:** los archivos quedan huérfanos en el almacén. La limpieza automática (ADR-25) no
  los borrará si superan el 20 % de lo revisado (el freno). Para borrarlos hay que subir
  `freno-proporcion` en una ejecución, como prevé ADR-25.
- **Datos fuera de la base:**
  - los segmentos de auditoría ya archivados en el almacén no se tocan;
  - las anclas publicadas en GitHub tampoco: quedan como reemplazadas.
- **Reencadenamiento y anclas:** cualquiera con acceso a la base podría insertar un
  reencadenamiento para excusar una diferencia con las anclas. Por eso la verificación los
  muestra siempre, con su motivo y su migración, y no los oculta.
