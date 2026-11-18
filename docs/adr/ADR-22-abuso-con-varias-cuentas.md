# ADR-22: Abuso con varias cuentas: límites por cuenta y antigüedad para validar

## Estado
Aceptada.

## Contexto
El gateway solo limitaba `/auth` (20 por minuto) y las denuncias anónimas (5 por
minuto), en ambos casos por IP (RNF-SEG5). Crear reportes y validar no tenían límite, y el
registro es libre. Alguien con varias cuentas y un script podía mandar cientos de reportes
por minuto:

- **Contamina los datos:** los reportes falsos inflan los indicadores y los mapas que ven la
  SSPD y el público.
- **Satura al operador** con casos que no existen.
- **Facilita el fraude en la validación comunitaria (R13):** bastan 3 confirmaciones netas
  de cuentas distintas (ADR-08), y con cinco cuentas eso se consigue fácil. El caso no se
  cierra solo, pero el reporte queda marcado como validado.
- **Abre otra vía al pico de carga del escenario E7,** esta vez provocado a propósito.

Un límite **por IP** no sirve aquí: en Colombia muchos usuarios móviles comparten IP detrás
del NAT del operador (compensación T4), y frenaría a vecinos legítimos justo en un pico.

Un límite por cuenta resuelve lo primero, pero no lo segundo: inflar no exige muchas
solicitudes por cuenta, sino muchas cuentas con un voto cada una. Para eso hace falta una
regla de negocio.

## Decisión

### Límite por cuenta en el gateway
`FiltroLimitePorCuenta` corre después de Spring Security, así que ya conoce la cuenta: es
el `sub` del JWT. Cada acción tiene dos ventanas fijas, por minuto (ráfagas) y por hora
(goteo sostenido):

| Acción | Por minuto | Por hora |
|---|---|---|
| `POST /api/v1/reportes` | 5 | **10** |
| `POST /api/v1/reportes/{id}/validaciones` | 10 | **30** |
| `POST /api/v1/reportes/{id}/evidencias` | 10 | 30 (3 fotos por cada uno de los 10 reportes) |

El límite es el de la hora; el del minuto solo frena ráfagas.

- **Al pasarse:** responde `429` problem+json con `Retry-After`.
- **Qué no se limita:** las consultas, y las otras cuentas no se ven afectadas aunque
  compartan IP.
- **Configuración:** los valores se ajustan en `servired.limites.por-cuenta.*`.

### Sin límites nuevos por IP
- No se agrega ningún límite por IP, ni siquiera al registro, para respetar T4. Un cupo de
  cuentas nuevas por IP bloquearía a los vecinos que se registran a la vez desde el mismo
  NAT durante un pico.
- Siguen los límites por IP que ya existían: `/auth`, 20 por minuto, y denuncias, 5 por
  minuto.

### Antigüedad mínima para validar
- **Claim nuevo:** Identidad incluye en el JWT el claim `creada`, con la fecha de creación
  de la cuenta en segundos epoch. Como el JWT va firmado, el cliente no puede alterarlo.
- **Regla en Reportes:** el voto de una cuenta creada hace menos de **24 h**
  (`VALIDACION_ANTIGUEDAD_MINIMA_CUENTA`) se registra, pero no suma a los conteos ni al
  umbral.
  - La respuesta lo indica con `cuentaParaUmbral: false`.
  - Ese ciudadano ya votó, así que no puede repetir el voto cuando su cuenta envejezca.
- **Persistencia:** guarda la marca en `reportes.validaciones.cuenta` (migración V10), y
  todos los conteos filtran por ella. Los votos anteriores siguen contando.
- **Tokens sin el claim** (emitidos antes del cambio): el voto suma. El token de acceso
  dura minutos y el siguiente ya trae el claim.

## Alternativas descartadas
- **Un voto por red por reporte:** el gateway enviaría a Reportes un hash de la IP salado
  por reporte. Rompe la minimización actual (la IP no llega a ningún servicio) y perjudica
  a vecinos que comparten red o el CGNAT del operador móvil.
- **Solo límites de frecuencia:** frenan a una cuenta, pero no a muchas cuentas con un
  voto cada una.
- **Cupo de cuentas nuevas por IP:** se implementó con 30 por hora y se retiró. Contradice
  T4: en un pico, muchos vecinos legítimos se registran a la vez detrás del mismo NAT.
- **Verificar el teléfono o el correo:** sube mucho la barrera de entrada y tiene costo
  (SMS). Queda como mejora si el abuso persiste.

## Consecuencias
- **Ciudadano nuevo:** el primer día su voto no ayuda a validar. Lo sabe por
  `cuentaParaUmbral`, y las apps pueden mostrarlo. Sus reportes cuentan desde el primer
  minuto.
- **Atacante:**
  - **Inundar:** cada cuenta puede crear a lo sumo 10 reportes por hora.
  - **Inflar validaciones:** debe crear las cuentas con un día de anticipación. No es
    imposible, pero ya no es inmediato.
  - **Crear cuentas:** solo lo frena el límite de `/auth` por IP. Si se observa creación
    masiva, la mejora es verificar el correo o el teléfono, no un límite por IP.
- **Varias réplicas del gateway:** los contadores viven en memoria y cada réplica tiene los
  suyos, así que con N réplicas el límite efectivo es hasta N veces el configurado. Un
  contador compartido (Redis) queda como mejora si el gateway se escala.
- **Pruebas e2e:** `registrar_y_entrar` envejece la cuenta 2 días por defecto, porque las
  suites modelan ciudadanos establecidos. `abuso-por-cuenta.py` usa cuentas nuevas a
  propósito.
