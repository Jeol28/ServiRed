# ADR-29: Archivado del registro de auditoría conservando la cadena de huellas

## Estado
Aceptada. Resuelve la mejora pendiente de ADR-16 («el registro crece sin límite»).

## Contexto
`auditoria.registros` es de solo inserción y está encadenado: cada fila guarda el SHA-256 de
la anterior junto con su contenido (`auditoria.huella`). Crece sin límite:
- **Volumen local:** las pruebas ya acumulaban unos 7000 registros.
- **Tamaño por registro:** en la base, con índices, ocupa varias veces lo que pesa
  comprimido (136 bytes).
- **Límite:** el plan gratuito de Supabase tiene 500 MB.

Borrar registros viejos rompería la cadena: el primero que quedara apuntaría a un hash que
ya nadie puede comprobar, y no se distinguiría de una manipulación. Además, la auditoría
debe conservarse: el SAD no fija un plazo de retención.

## Decisión
**Archivar no es destruir.** Los registros viejos salen de la base a **segmentos** en el
almacén de objetos, y la cadena sigue siendo verificable de punta a punta.

- **Qué se archiva:**
  - cada día, los meses completos (UTC, por `registrado_en`) más viejos que la ventana en
    la base: `AUDITORIA_MESES_EN_BASE`, **12** por defecto;
  - segmentos de a lo sumo 50 000 registros;
  - un rango contiguo de secuencias.
- **Segmento:**
  - archivo `auditoria/segmentos/<desde>-<hasta>.jsonl.gz`, en JSON Lines comprimido;
  - una cabecera, y luego cada registro con todos sus campos en la forma exacta en que los
    usa `auditoria.huella`: detalle canónico de JSONB y fechas UTC con microsegundos;
  - cualquiera puede recalcular la cadena sin SERVI-RED. Pesa unos 136 bytes por registro.
- **Índice:** `auditoria.segmentos` guarda, para cada segmento, el rango, la cantidad, el
  `hash_anterior` de su primer registro, el `hash` de su último registro, el SHA-256 y el
  tamaño del archivo. Es de solo inserción, igual que el registro.
- **Pasos (`ArchivoAuditoria`):**
  1. Lee el rango y **recalcula su cadena en la base**, enlazada con el último segmento. Si
     está rota, **no archiva**: deja el tramo alterado en la base como evidencia, lo informa
     y dispara la alerta `ArchivoDeAuditoriaDetenido` (crítica).
  2. Sube el archivo y lo vuelve a descargar para confirmar los mismos bytes.
  3. En una transacción, inserta el segmento en el índice y borra los registros que cubre.
     El trigger del registro solo permite ese DELETE con la marca de la transacción de
     archivado y si un segmento ya registrado cubre la fila. UPDATE y TRUNCATE siguen
     prohibidos siempre.
- **Encadenamiento continuo:** un registro nuevo enlaza con el último registro de la base,
  o, si todo está archivado, con el `hash_final` del último segmento.
- **Verificación:**
  - **Rápida** (`GET /api/v1/auditoria/integridad`, administrador): recalcula los registros
    en la base; exige que el primero enlace con el último segmento y que los segmentos
    enlacen entre sí. Informa el total, cuántos hay en la base y archivados, cuántos
    segmentos, `primeraRota` y `segmentoRoto`.
  - **Profunda** (`infra/operacion/auditoria-archivo.py verificar`): descarga cada segmento,
    compara su SHA-256 con el índice y recalcula todas sus huellas en PostgreSQL con la misma
    función.
- **Operación:** `auditoria-archivo.py` tiene los comandos `listar`, `verificar` y
  `archivar [--hasta]`. Las alertas son `ArchivoDeAuditoriaDetenido` y
  `ArchivoDeAuditoriaFallando`.

## Alternativas descartadas
- **Borrar lo viejo:** rompe la cadena y destruye evidencia.
- **Particionar la tabla y mover particiones a otro esquema:** el registro sigue ocupando la
  misma base, que es lo que se quiere aliviar.
- **Una tabla de archivo en la misma base:** mismo problema de espacio.
- **Sellar con un hash de resumen sin guardar los registros:** permite probar que existieron,
  pero no consultarlos ni recalcularlos.

## Consecuencias
- **Ventana:** la base conserva el último año y el almacén, todo lo anterior. Ajustarla es
  un cambio de configuración.
- **No hay borrado definitivo:** si se fija un plazo legal de retención, borrar los segmentos
  más viejos que ese plazo es una decisión aparte. La cadena lo tolera si se borran desde el
  principio y se conserva el índice.
- **Consulta:** la API de consulta (`GET /api/v1/auditoria/registros`) solo ve lo que sigue
  en la base. Para revisar lo archivado se descarga el segmento, que es un JSONL legible.
- **Ubicación:** los segmentos van al mismo bucket privado que las evidencias, bajo
  `auditoria/`. La limpieza de huérfanos (ADR-25) solo toca `reportes/` y `denuncias/`.
- **Sin inmutabilidad en el almacén:** ni Supabase Storage ni SeaweedFS ofrecen bloqueo de
  objetos (S3 Object Lock). Una alteración del archivo se detecta (SHA-256 en la base,
  enlaces de la cadena), pero no se impide. Para una garantía mayor, se puede copiar cada
  segmento a un almacenamiento WORM externo o publicar periódicamente el último hash.
- **Pruebas:**
  - Integración, `ArchivoAuditoriaIntegracionTest` contra PostgreSQL y SeaweedFS:
    - archiva y la cadena sigue íntegra;
    - lo nuevo enlaza con lo archivado, también con la base vacía;
    - la verificación profunda funciona y detecta un archivo alterado;
    - solo se borra lo cubierto y el índice es inmutable;
    - no se archiva un tramo manipulado.
  - e2e `archivo-auditoria.py`: archiva 6959 registros reales en un segmento de 925 kB en
    menos de 2 s.
