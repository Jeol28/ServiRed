# ADR-25: Limpieza de objetos huérfanos del almacén de evidencias

## Estado
Aceptada.

## Contexto
Persistencia sube cada foto al almacén **antes** de la transacción que guarda su
referencia, para no tener una conexión de la base ocupada durante la subida. Si la
transacción falla, compensa borrando el objeto. Pero esa compensación es de mejor
esfuerzo y puede fallar: el almacén no responde, o el proceso se cae entre la subida y el
commit. En esos casos la foto queda en el bucket sin ninguna evidencia que la referencie,
es decir, huérfana.

Una foto huérfana trae dos problemas:
- **Costo:** nadie puede verla ni pedirla, pero ocupa espacio. El plan gratuito de Supabase
  Storage es limitado.
- **Minimización de datos:** si viene de una denuncia anónima, se conserva una foto sin
  ningún motivo.

## Decisión
Persistencia ejecuta una tarea periódica, `TareaLimpiezaAlmacen`, que llama al caso de
uso `LimpiezaAlmacen`.

1. **Recorre** el bucket por páginas de hasta 1000 objetos, solo bajo los prefijos que
   escribe Persistencia: `reportes/` y `denuncias/`. No toca nada más.
2. **Descarta los recientes:** no evalúa objetos subidos hace menos de 1 hora (`gracia`),
   porque su transacción puede estar todavía en curso.
3. **Busca huérfanos:** por cada página, pregunta a la base qué claves referencia
   `reportes.evidencias`. Las demás son huérfanas. Las referencias nunca se borran, así
   que una clave sin fila no tiene dueño.
4. **Freno:** si hay 5 o más huérfanos y superan el 20 % de lo revisado, no borra nada.
   Registra un ERROR y sube la métrica `servired_almacen_limpieza_frenadas_total`, que
   dispara la alerta `LimpiezaDelAlmacenFrenada`. Una proporción así no es residuo normal:
   casi seguro es una configuración cruzada, por ejemplo una base vacía o restaurada junto
   a un bucket con fotos. Sin el freno, la tarea borraría todas las fotos, y eso no se
   puede deshacer.
5. **Borra con cuidado:** antes de borrar cada huérfano vuelve a comprobar su referencia.
   Si un borrado falla, lo cuenta y sigue con los demás.

- **Frecuencia:** cada 6 horas (`ALMACEN_LIMPIEZA_INTERVALO`); la primera vez, 10 minutos
  después de arrancar. `ALMACEN_LIMPIEZA_ACTIVA=false` la apaga.
- **Sin transacciones abiertas:** nunca abre una mientras lista o borra.
- **Varias réplicas:** pueden ejecutarla a la vez sin coordinarse, porque borrar dos veces
  el mismo objeto no tiene efecto.
- **Si el almacén o la base no responden:** sube `servired_almacen_limpieza_fallas_total`,
  y la tarea se reintenta en la siguiente ejecución. Tres fallas en un día disparan
  `LimpiezaDelAlmacenFallando`.
- **Métrica de borrados:** `servired_almacen_huerfanos_borrados_total`.

## Alternativas descartadas
- **Subir la foto dentro de la transacción:** es lo que se corrigió antes; ocupaba una
  conexión del pool durante toda la subida.
- **Reglas de ciclo de vida del bucket:** solo saben borrar por antigüedad, no por falta de
  referencia, y Supabase Storage no las ofrece.
- **Registrar una «subida pendiente» antes de subir:** cambia el flujo de cada foto y
  agrega una escritura más, para resolver un caso raro que una limpieza periódica cubre
  igual de bien.

## Consecuencias
- **Cuánto vive un huérfano:** a lo sumo la gracia más el intervalo, unas 7 horas.
- **Si la tarea se frena,** alguien debe revisar la configuración. Si de verdad son
  huérfanos, se pueden subir `freno-minimo` o `freno-proporcion` para una ejecución.
- **Pruebas:**
  - `LimpiezaAlmacenTest`: borra solo los viejos sin referencia de los dos prefijos;
    respeta la gracia; frena; no borra un objeto que se referenció mientras tanto; una
    falla no detiene las demás.
  - `LimpiezaAlmacenIntegracionTest`: lo mismo contra SeaweedFS y PostgreSQL reales.
