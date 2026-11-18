# ADR-22: Abuso con varias cuentas: límites por cuenta y antigüedad para validar

## Estado
Aceptada.

## Contexto
El gateway solo limitaba `/auth` (fuerza bruta) y las denuncias anónimas (spam), en ambos
casos por IP (RNF-SEG5). Las escrituras de los ciudadanos no tenían límite, y el registro
es libre. Eso dejaba dos abusos posibles:

- **Inundar:** una cuenta puede crear reportes, votos o fotos sin freno y saturar
  Persistencia, el almacén y los operadores.
- **Inflar validaciones:** un corte queda validado con 3 confirmaciones netas de
  ciudadanos distintos a menos de 1 km (ADR-08), y la validación sube su prioridad. Con 3
  cuentas recién creadas basta.

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
| `POST /api/v1/reportes` | 6 | 30 |
| `POST /api/v1/reportes/{id}/validaciones` | 20 | 120 |
| `POST /api/v1/reportes/{id}/evidencias` | 10 | 60 |

- **Al pasarse:** responde `429` problem+json con `Retry-After`.
- **Qué no se limita:** las consultas, y las otras cuentas no se ven afectadas aunque
  compartan IP.
- **Configuración:** los valores se ajustan en `servired.limites.por-cuenta.*`.

### Límite de registro por IP
- `POST /api/v1/auth/registro` tiene, además del límite de `/auth` (20 por minuto), un cupo
  de **30 cuentas nuevas por hora por IP** (`LIMITE_REGISTROS_POR_HORA`).
- Es generoso a propósito, porque muchos usuarios móviles comparten IP (CGNAT).
- La IP sigue sin guardarse: solo su SHA-256 con sal en memoria, como hasta ahora.

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
- **Verificar el teléfono o el correo:** sube mucho la barrera de entrada y tiene costo
  (SMS). Queda como mejora si el abuso persiste.

## Consecuencias
- **Ciudadano nuevo:** el primer día su voto no ayuda a validar. Lo sabe por
  `cuentaParaUmbral`, y las apps pueden mostrarlo. Sus reportes cuentan desde el primer
  minuto.
- **Atacante:** para inflar validaciones debe crear las cuentas con un día de anticipación
  y a lo sumo 30 por hora por IP. No es imposible, pero ya no es inmediato.
- **Varias réplicas del gateway:** los contadores viven en memoria y cada réplica tiene los
  suyos, así que con N réplicas el límite efectivo es hasta N veces el configurado. Un
  contador compartido (Redis) queda como mejora si el gateway se escala.
- **Pruebas e2e:** `registrar_y_entrar` envejece la cuenta 2 días por defecto, porque las
  suites modelan ciudadanos establecidos. `abuso-por-cuenta.py` usa cuentas nuevas a
  propósito.
