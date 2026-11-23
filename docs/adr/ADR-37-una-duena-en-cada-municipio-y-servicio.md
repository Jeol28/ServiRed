# ADR-37: Una empresa dueña en cada municipio y servicio

## Estado
Aceptada. Completa [ADR-35](ADR-35-empresas-reales-y-zonas-de-servicio.md).

## Contexto
Cada rincón de Colombia debe tener al menos una empresa por servicio. Después de ADR-35, de los
1 122 municipios y áreas no municipalizadas quedaban sin ninguna empresa:

| Servicio | Sin empresa | Por qué |
|---|---|---|
| Energía | 22 | Zonas no interconectadas de Amazonas, Guainía, Vaupés, Guaviare y Vichada |
| Gas | 381 | Sin red de gas natural: se usa gas en cilindro (GLP) |
| Acueducto | 28 | Ningún prestador registrado en el RUPS (Amazonía, La Guajira…) |
| Alcantarillado | 84 | Ídem (Bolívar, Amazonía, Chocó…) |
| Aseo | 25 | Ídem |

En esas zonas, los casos quedaban sin empresa.

## Decisión
Se tomó con el usuario.
- **Gas:** queda como dueña la **distribuidora de GLP** del RUPS con domicilio más cercano
  (por centroide DIVIPOLA), primero entre las del mismo departamento.
  - **Aproximado:** el RUPS no dice en qué municipios vende cada una, y 46 de las 125 tienen
    domicilio en Bogotá.
- **Energía, acueducto, alcantarillado y aseo:** queda **el municipio en prestación directa**
  (Ley 142 de 1994, art. 6) o, en las áreas no municipalizadas, **el departamento**.
  - **NIT:** se usa el del RUPS si el municipio o el departamento figura allí como prestador
    (por ejemplo, el municipio de Miraflores, Guaviare). Si no, la empresa queda sin NIT
    (`catalogo.empresas.nit` pasa a admitir nulos) y su identificador es el UUID v5 de su
    código DIVIPOLA.
  - **Nombre:** «MUNICIPIO DE X (PRESTACIÓN DIRECTA)» o «DEPARTAMENTO DE X (PRESTACIÓN
    DIRECTA)». Se les pueden crear operadores como a cualquier empresa.
- **V24:** generada con `herramientas/generar-prestadores.py --complemento`. Solo agrega
  empresas y coberturas (`ON CONFLICT DO NOTHING`), así que V21 no cambia y la herramienta la
  sigue generando idéntica.
  - **Resultado:** 112 empresas nuevas o reutilizadas, de las cuales 58 no tienen NIT.
  - **Invariante:** toda cabecera y todo resto rural tiene dueña en los 5 servicios. Lo
    comprueba una prueba de integración.
- **V25:** enruta a la nueva dueña los casos y las moderaciones que habían quedado sin empresa,
  con historia y registro en `catalogo.reasignacion_empresas`.

## Alternativas descartadas
- **La empresa más cercana con ese servicio:** dejaría, por ejemplo, la de Leticia para La
  Chorrera, a cientos de kilómetros.
- **La distribuidora de gas natural más cercana:** no llega con red a esos municipios.
- **Dejar los huecos:** contradice el requisito.

## Consecuencias
- **Sin casos sin empresa:** desde la API ya no se produce un caso sin empresa. El código que lo
  maneja se conserva por si la cobertura cambia, y lo cubren las pruebas de Atención.
- **Asignaciones aproximadas:** en gas (GLP) y en prestación directa conviene revisarlas con la
  Superservicios. Se corrigen con la herramienta y una migración nueva.
- **Sin NIT:** en el portal, las empresas sin NIT se muestran sin él y se encuentran por nombre,
  servicio y territorio.
