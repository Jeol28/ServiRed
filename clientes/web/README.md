# Portales web (Angular)

Workspace Angular 22 con dos aplicaciones y una librería compartida. Solo hablan
con el **gateway** a través de `/api/` en su mismo origen (ver
[ADR-17](../../docs/adr/ADR-17-portales-web.md)).

| Proyecto | Para quién | Pantallas |
|---|---|---|
| `portal-publico` | Ciudadanía (sin cuenta) | Mapa de zonas con reportes (celdas de ~1 km, k ≥ 3), indicadores por servicio y territorio y datos abiertos (CSV/JSON). Reportes y denuncias se hacen solo desde las apps móviles |
| `portal-operador` | Operador, regulador, administrador | Ingreso, bandeja de casos con filtros, detalle (atender, asignar, prioridad, resolver, cerrar, enrutar), mapa operativo, moderación de denuncias, exportación regulatoria, auditoría y alertas (verificación de integridad), cuentas |
| `compartido` | Ambos | Modelos del contrato, sesión y guardias, interceptores (JWT, `X-Correlation-Id`), servicios de API, mapa Leaflet con íconos por servicio, menú lateral, estilos |

Los dos portales usan el estilo de las apps móviles:
- una barra flotante con ☰ y un menú lateral;
- el mapa a pantalla completa, con chips de servicio (⚡ 💧 🔥 🌊 🗑️) y tarjetas abajo.

El portal público abre en el mapa. En el de operación, el mapa operativo ocupa toda la
ventana; las bandejas y tablas siguen igual.

Menú por rol en el portal de operación:

| Sección | Operador | Regulador | Administrador |
|---|:-:|:-:|:-:|
| Casos y denuncias | gestiona (su empresa) | consulta | gestiona y enruta |
| Mapa operativo | ✔ (su empresa) | ✔ | ✔ |
| Exportación, auditoría | | ✔ | ✔ (+ integridad) |
| Cuentas | | | ✔ |

## Ejecutar

Con todo el compose arriba (`docker compose up -d --build --wait` en la raíz):

- Portal público: http://localhost:4300
- Portal de operación, en la misma dirección: http://localhost:4300/operacion/ (botón
  «Acceso del personal»; primer administrador: `IDENTIDAD_ADMIN_CORREO` /
  `IDENTIDAD_ADMIN_CLAVE` de `.env`). También responde directo en 127.0.0.1:4200.

Para desarrollar con recarga en caliente (Node 24, el gateway en :8080):

```bash
npm ci
npx ng serve portal-publico --port 4300 --proxy-config proxy.conf.json
npx ng serve portal-operador --port 4200 --proxy-config proxy.conf.json
```

## Catálogo de departamentos y municipios

Los filtros de territorio (Indicadores, Datos abiertos y la bandeja de Casos) usan dos
listas desplegables: al elegir el departamento solo se ofrecen sus municipios, con su
nombre oficial del DANE (p. ej. «Santiago de Cali»). La bandeja de casos filtra también
solo por departamento (`codigoDepartamento` en `GET /casos`).

- **Origen:** `compartido/src/lib/territorio/divipola.ts` se genera desde la misma semilla
  DIVIPOLA de la base (`V3__semilla_divipola.sql`), así que no hace falta otra API.
- **Carga:** se descarga aparte (~7 kB comprimido) la primera vez que se abren los filtros.
- **Si cambia la semilla:** `python3 herramientas/generar-divipola.py`.

## Pruebas

```bash
npx ng test compartido --watch=false        # sesión, interceptores, errores, filtros
npx ng test portal-operador --watch=false   # ingreso, menú por rol, acciones del caso
npx ng test portal-publico --watch=false    # navegación, supresión de indicadores
npx ng build portal-publico && npx ng build portal-operador
python3 ../../pruebas/e2e/portales-web.py   # e2e: cabeceras, proxy, anonimato, límite
```

Vitest + jsdom con `HttpTestingController`; no hace falta navegador.

## Seguridad y privacidad

- JWT de acceso solo en memoria; refresco en `sessionStorage` y de un solo uso
  (renovación compartida). Nada en `localStorage` ni cookies.
- Las denuncias anónimas solo se hacen desde las apps. El portal de operación las modera,
  y los interceptores nunca envían JWT ni correlationId a `/api/v1/denuncias`.
- CSP sin scripts en línea, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`;
  ningún portal pide la ubicación del navegador.
- Textos de usuario en el mapa con `textContent` (nunca HTML).

## Imagen

`Dockerfile` multi-etapa (Node 24 → nginx sin privilegios en :8080), una imagen
por portal con `--build-arg PROYECTO=portal-publico|portal-operador`. La
configuración está en [`nginx/portal.conf.template`](nginx/portal.conf.template).
