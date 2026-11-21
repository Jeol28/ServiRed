# ADR-32: Recálculo del municipio de los reportes ubicados por centroide

## Estado
Aceptada. Completa ADR-27.

## Contexto
ADR-27 cambió la ubicación de los reportes nuevos: ahora el municipio sale del polígono
oficial del DANE en vez del centroide más cercano. Los reportes guardados antes conservaban el
municipio del centroide. Con eso, quedaban errados:
- la vista de Indicadores y los indicadores por municipio;
- los casos, incluida la empresa a la que se enrutaron.

Corregirlos cambia datos ya usados, así que el cambio tiene que quedar registrado y no puede
quitarle un caso a quien ya lo atiende.

## Decisión
La migración **V17** llama una vez a la función `reportes.recalcular_municipios(migracion)`.
Para cada reporte cuyo polígono da un municipio distinto del guardado:

1. **Registro de qué cambió:** una fila en `reportes.recalculo_municipio`, de solo inserción,
   con:
   - el reporte y los municipios anterior y nuevo;
   - el caso, su estado y las empresas anterior y nueva, y si se reenrutó;
   - la fuente, la migración y la fecha.
2. **Reporte y vista de Indicadores:** se actualiza el municipio.
3. **Caso:**
   - se actualiza el municipio;
   - se agrega una entrada en su historia («Municipio corregido de X a Y con los límites
     oficiales del DANE…»);
   - sube la versión: un operador con el caso abierto recibe un conflicto y lo recarga.
4. **Empresa, solo si el caso está intacto:** RECIBIDO, sin asignar, y con la empresa que le
   dio el enrutamiento automático en el municipio anterior.
   - **Regla:** la misma de Atención: entre las empresas que cubren municipio y servicio, la
     de menor id; si ninguna, queda sin empresa para enrutamiento manual.
   - **Casos que conservan su empresa:** los que ya se atienden, resueltos o cerrados, y los
     enrutados a mano.
5. **Cadena de auditoría:** un resumen como `accion.auditada` (`MUNICIPIOS_RECALCULADOS`, con
   origen `reportes`), con los conteos y el nombre de la tabla de registro. Entra por el outbox.

Las **denuncias anónimas no se recalculan**: pasan por moderación y no se publican por
municipio.

## Alternativas descartadas
- **No corregir lo viejo:** deja convivir dos criterios en los mismos indicadores.
- **Reenrutar todos los casos abiertos:** le quita casos a operadores que ya los atienden.
- **Corregir solo el municipio:** deja casos intactos en empresas sin cobertura, que habría que
  reenrutar a mano.
- **Hacerlo con un script por fuera de Flyway:** no quedaría versionado ni se aplicaría solo en
  cada entorno.

## Consecuencias
- **Ya exportado:** lo exportado a la SSPD antes de la migración no cambia. Si una exportación
  posterior difiere, `reportes.recalculo_municipio` explica cada diferencia.
- **Visible para el ciudadano:** ve el municipio corregido en su reporte. No se agrega una
  entrada en la historia del reporte: el cambio es de ubicación, no de estado.
- **Límites nuevos del DANE:** se carga la geometría nueva (como en V13) y se llama de nuevo
  a la función con otro nombre de migración. Cada recálculo queda registrado por separado.
- **Medido en la base local:**
  - 12 de 2755 reportes cambiaron (La Calera → Bogotá) y sus 12 casos intactos se
    reenrutaron;
  - la migración tardó 0,5 s;
  - la vista quedó alineada (R14) y la cadena de auditoría íntegra.
- **Pruebas:** `RecalculoMunicipioIntegracionTest`:
  - un caso intacto se reenruta; uno en atención y uno enrutado a mano conservan su empresa;
  - un reporte bien ubicado no cambia; un reporte sin caso también se corrige;
  - el registro es inmutable y queda el resumen en el outbox;
  - correrla de nuevo no cambia nada.
