# ADR-31: Anclas externas de la cadena de auditoría

## Estado
Aceptada. Complementa ADR-29.

## Contexto
ADR-29 archiva el registro de auditoría en segmentos del almacén de objetos. Ni Supabase
Storage ni SeaweedFS ofrecen bloqueo de objetos, así que un segmento se puede reescribir.
Alterarlo a medias se detecta (SHA-256 en el índice, enlaces de la cadena). Pero quien tenga
acceso a la base y al bucket podría hacer una reescritura completa:
1. cambiar un registro de un segmento;
2. recalcular todas las huellas siguientes con la misma función;
3. desactivar los triggers y ajustar el índice del segmento.

La verificación interna seguiría diciendo «íntegra», porque todo es coherente entre sí. Hace
falta una referencia que el sistema no controle.

## Decisión
**Cada día se fija un ancla**: la fecha (UTC), la secuencia y el hash del último registro de
la cadena, el total de registros y los segmentos.

- **Dónde se guarda:** en `auditoria.anclas` (V16). Lo anclado no cambia: un trigger solo
  permite completar una vez dónde se publicó.
- **No se ancla una cadena rota:** sería publicar como bueno un estado alterado. En ese caso
  dispara la alerta `AnclaNoFijadaPorCadenaRota` (crítica).
- **Dónde se publica, fuera del sistema:**
  - **Un archivo en GitHub:** `auditoria/anclas.jsonl` en la rama propia `anclas-auditoria`
    del repositorio (`AUDITORIA_ANCLAS_GITHUB_REPOSITORIO`). Cada ancla es una línea y un
    commit (API de contenidos). La rama se crea desde la principal si no existe. El token
    (`AUDITORIA_ANCLAS_GITHUB_TOKEN`) es de grano fino, con «Contents: Read and write» solo
    sobre ese repositorio. Una regla de GitHub sobre la rama (sin force push ni borrado)
    impide reescribir lo publicado, incluso con el mismo token.
  - **La API pública** `GET /api/v1/auditoria/anclas`, sin token: cualquiera puede guardarla
    o archivarla. Solo contiene hashes y conteos, ningún dato de personas.
- **Frecuencia:** cada 6 horas; el ancla es una por día, así que las demás ejecuciones solo
  reintentan la publicación si falló.
- **Si no se publica:** sin destino configurado, o si GitHub falla más de 2 días, dispara la
  alerta `AnclaDeAuditoriaSinPublicar`. Eso cubre también un token vencido.
- **Verificación** (`infra/operacion/auditoria-anclas.py verificar`):
  - lee las anclas **de GitHub**, no de la base, que podría estar alterada;
  - busca el hash que hoy tiene cada secuencia, en la base o dentro de su segmento archivado;
  - si alguno no coincide, lo informa como posible reescritura de la historia.

## Alternativas descartadas
- **Solo un registro en el portal público:** lo sirve el mismo sistema, así que quien lo
  controle puede cambiarlo. Sirve como segunda vía para que terceros guarden copias, no como
  única.
- **Sellado de tiempo de un tercero (RFC 3161) o una cadena de bloques pública:** más fuertes,
  pero con costo, dependencias y operación nuevas. Un commit diario en GitHub da el mismo
  efecto práctico para el MVP.
- **Bloqueo de objetos (S3 Object Lock):** no lo ofrece ni el almacén local ni Supabase.

## Consecuencias
- **Qué detecta:** una reescritura de cualquier registro anterior al último ancla publicada,
  aunque la cadena interna siga coherente. La ventana es de un día: lo que pase entre el ancla
  y el siguiente día queda cubierto en el ancla siguiente.
- **Quien controle la VM** tiene el token y podría **agregar** anclas falsas desde ese momento,
  pero no reescribir las anteriores, por la regla de la rama. La historia previa al compromiso
  sigue protegida.
- **Sin GitHub configurado** (por ejemplo, en local), las anclas quedan en la base y en la API
  pública. La verificación lo dice: usa la base, que es menos confiable.
- **Pruebas:**
  - `AnclasAuditoriaIntegracionTest`, con GitHub simulado:
    - publica una vez por día, crea la rama y el ancla es inmutable;
    - verifica desde lo publicado, también con todo archivado;
    - **una reescritura completa de un segmento** pasa la verificación interna y **choca con
      el ancla**;
    - con la cadena rota no se ancla.
  - Gateway y Auditoría: la ruta es pública.
  - e2e `anclas-auditoria.py`.
