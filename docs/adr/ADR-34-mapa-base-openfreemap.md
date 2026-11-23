# ADR-34: Mapa base vectorial de OpenFreeMap

## Estado
Aceptada. Reemplaza el mapa base de ADR-17 (portales) y ADR-18 (apps), que usaban los
mosaicos raster estándar de OpenStreetMap (`tile.openstreetmap.org`).

## Contexto
El mapa base de OpenStreetMap no gustó: es una imagen raster recargada de colores, que
compite con los puntos, celdas e íconos de SERVI-RED. Además, la política de uso de
`tile.openstreetmap.org` no está pensada para aplicaciones en producción.

## Decisión
- **Proveedor:** el estilo **Positron** de [OpenFreeMap](https://openfreemap.org)
  (`https://tiles.openfreemap.org/styles/positron`):
  - **Mapa vectorial:** se ve nítido a cualquier zoom.
  - **Estilo claro y sobrio:** las marcas resaltan.
  - **Costo:** sin clave, cuenta ni tarjeta, y sin límites publicados.
  - **Datos:** sigue siendo OpenStreetMap (vía OpenMapTiles).
  - **Origen único:** estilo, mosaicos, fuentes e íconos salen de `tiles.openfreemap.org`.
- **Apps (Android e iOS):** MapLibre ya dibuja estilos vectoriales; se cambia el estilo raster
  en línea por la URL del estilo. Las capas propias (zonas, marcas, ubicación, selección)
  no cambian.
- **Portales:** se conserva Leaflet (marcas, ventanas, clics y teclado no cambian) y el mapa
  base lo dibuja MapLibre GL JS dentro de una capa de Leaflet
  (`@maplibre/maplibre-gl-leaflet` 0.1.4, `maplibre-gl` 5.24.0).
  - **Tamaño:** MapLibre va en el fragmento diferido de las páginas con mapa (~1,2 MB sin
    comprimir); la carga inicial no crece.
  - **CSP:** `connect-src` agrega `https://tiles.openfreemap.org` (el estilo y los mosaicos
    se piden con `fetch`) y `worker-src 'self' blob:` (MapLibre decodifica en un web worker).
    `img-src` ya no necesita un dominio externo.
- **Atribución:** OpenFreeMap, OpenMapTiles y OpenStreetMap, como piden sus licencias.

## Alternativas descartadas
- **CARTO:** solo cambiaba la URL raster, pero el uso gratuito es no comercial y con tope.
- **MapTiler o Mapbox:** piden clave y tienen cuota.
- **Google Maps:** exige facturación con tarjeta, reescribir los mapas con sus SDK y le
  entrega a Google la zona que mira cada usuario.
- **Reemplazar Leaflet por MapLibre GL JS en los portales:** reescribir marcas, ventanas y
  accesibilidad por teclado sin ganar nada visible.

## Consecuencias
- **Privacidad:** OpenFreeMap ve qué zona del mapa carga cada usuario, igual que antes
  OpenStreetMap. No recibe identificadores de SERVI-RED.
- **Dependencia externa:** si OpenFreeMap cae, el mapa base queda en blanco, pero las marcas y
  los datos siguen funcionando. OpenFreeMap se puede alojar por cuenta propia si hiciera falta.
- **Requisito del navegador:** los portales necesitan WebGL. Es estándar en navegadores
  actuales.
