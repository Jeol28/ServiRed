# ADR-14 · Atención de casos: enrutamiento, prioridad, ciclo de vida y acceso

**Estado:** aceptada · **Fecha:** 2026-11-17 · **Requisitos:** RF-5, RF-6, RF-7, RNF-SEG2

## Contexto
RF-6 pide enrutar el caso al operador responsable según servicio y ubicación;
RF-7, que el operador filtre, priorice, asigne, actualice y cierre; RF-5, someter
las denuncias a validación. El SAD no fija la fórmula de prioridad, las
transiciones permitidas ni cómo se segrega el acceso entre empresas.

## Decisión
- **Enrutamiento:** por la cobertura declarada (empresa × municipio × servicio).
  Si varias empresas cubren, se elige la de menor identificador (determinista);
  si ninguna, el caso queda **sin empresa** y lo enruta un administrador.
  **Reemplazado por [ADR-35](ADR-35-empresas-reales-y-zonas-de-servicio.md):** una sola empresa dueña por zona de servicio (localidad o comuna,
  cabecera o resto rural); el administrador solo enruta a esa dueña.
- **Prioridad:** puntaje = magnitud (VIVIENDA 1, CUADRA 2, BARRIO 3, SECTOR 4)
  + 1 si la comunidad validó el corte + 1 si es gas (riesgo para la seguridad).
  1 → BAJA, 2–3 → MEDIA, 4 → ALTA, 5+ → CRITICA. Se recalcula al cambiar la
  validación; si un operador la fija a mano, el cálculo ya no la toca.
- **Ciclo de vida:** RECIBIDO → EN_ATENCION → RESUELTO → CERRADO. Atender asigna
  el caso a quien lo atiende si no tenía responsable. Resolver exige la
  resolución. Cerrar sin resolver (duplicado, improcedente) exige el motivo. El
  restablecimiento registrado por el ciudadano resuelve el caso abierto.
- **Acceso (además del gateway):** el operador solo ve y gestiona los casos y
  denuncias de **su empresa** (claim `emp`); los demás responden 404 para no
  revelar que existen. El regulador consulta todo y no gestiona. El
  administrador gestiona todo y es el único que enruta. Solo se asigna a cuentas
  que Persistencia confirma como operadores activos de la empresa del caso.
- **Concurrencia:** cada caso y moderación tiene versión optimista; si dos
  operadores actúan a la vez, el segundo recibe 409 `VERSION`. Las reacciones a
  eventos reintentan leyendo de nuevo.
- **Eventos:** `caso.actualizado` cuando cambia estado, prioridad, asignación o
  empresa; además `caso.cerrado` al cerrar. Ambos salen del outbox en la misma
  transacción. Reportes los traduce al estado del ciudadano (ADR-07).
- **Moderación:** la denuncia recibida se asigna a la empresa con cobertura (desde [ADR-35](ADR-35-empresas-reales-y-zonas-de-servicio.md), la
  dueña de la zona de la denuncia); el
  operador la marca VALIDADA o DESCARTADA con una nota (una sola vez). El
  contenido sigue en el dominio de reportes; la decisión, en el de atención.

## Consecuencias
- El primer evento de un reporte puede llegar antes que su caso (p. ej. una
  validación muy rápida): Atención lo trata como falla transitoria y lo
  reintenta por la cola de reintento hasta que el caso exista.
- La prioridad no considera aún la duración acumulada ni la densidad de
  reportes en la zona; se puede agregar sin cambiar contratos.
- Los eventos `restablecimiento.registrado` y `denuncia.recibida` emitidos
  durante la fase 4 se ignoraron y no se recuperan (solo afecta datos de prueba).
