# ADR-13 · Evidencias fotográficas y almacenamiento de objetos

**Estado:** aceptada · **Fecha:** 2026-11-16 · **Requisitos:** RF-1, RF-5, RNF-SEG3, RNF-SEG4, RNF-SEG5

## Contexto
RF-1 incluye una fotografía en el reporte y RF-5 admite evidencia en las
denuncias. El SAD asigna las fotos a Supabase Storage y establece que solo
Persistencia tiene credenciales de Supabase; su "gestor de transacciones" guarda
el reporte "junto con la referencia de su evidencia". RNF-SEG5 exige limitar la
carga de archivos peligrosos y ADR-09 eliminar los metadatos EXIF.

## Decisión
- **Quién hace qué:** Reportes (gestor de evidencia) valida y **sanea** la imagen;
  Persistencia la sube al almacén y guarda la referencia. Ningún otro servicio ni
  cliente accede al almacén: el bucket es privado y las fotos se descargan por la
  API de Reportes, solo por el autor.
- **Validación:** solo JPEG o PNG, con el tipo detectado por la **firma del
  archivo** (no por la extensión ni el Content-Type); máximo 5 MB, 10.000 px por
  lado y 40 megapíxeles, comprobados en la cabecera **antes** de decodificar
  (evita bombas de descompresión); máximo 3 fotos por reporte.
- **Saneamiento:** la imagen se decodifica y se vuelve a codificar con ImageIO
  (JPEG calidad 0,85 o PNG). Eso elimina EXIF, GPS, modelo del equipo y cualquier
  contenido incrustado. Se descarga con `nosniff` y `no-store`.
- **Almacén:** protocolo **S3**. En producción, el endpoint S3 de Supabase
  Storage (`https://<proyecto>.supabase.co/storage/v1/s3`); en local,
  **SeaweedFS 3.97** con una identidad obligatoria tomada de `.env`. Se cambia
  solo con `ALMACEN_S3_*`.
- **Consistencia:** el objeto se sube **antes** de abrir la transacción, y la
  transacción solo escribe filas: idempotencia, bloqueo del reporte, límite de 3 y la
  referencia. La clave del objeto es `reportes/<id>/<idEvidencia>.<ext>` o
  `denuncias/<id>/<idEvidencia>.<ext>` y se guarda también su SHA-256.
  - **Compensación:** si la transacción falla, el objeto se borra. También se borra
    si un reintento idempotente terminó sin referenciarlo (se consulta
    `clave_objeto`). Un objeto referenciado nunca se borra.
  - **Por qué no dentro de la transacción** (así era al principio): la conexión del
    pool, y el bloqueo del reporte, quedaban tomados mientras duraba la subida al
    almacén. Con un pico de reportes con foto y subidas lentas, el pool (10
    conexiones) se agotaba y frenaba también a los reportes sin foto.
  - Antes de subir se comprueba, sin transacción, que el reporte exista. El límite
    de fotos se decide dentro de la transacción, para que el reintento de la tercera
    foto devuelva el resultado y no un conflicto.
- **SDK de AWS:** los checksums se calculan solo cuando el servicio los exige
  (`WHEN_REQUIRED`). Con el valor por defecto del SDK 2.30+, los objetos viajan
  como `aws-chunked` con checksum al final y SeaweedFS los guardaba corruptos;
  una prueba de integración compara byte a byte lo guardado.

## Alternativas descartadas
- **MinIO:** dejó de publicar imágenes de Docker en 2025.
- **URLs prefirmadas al cliente:** exponen el almacén fuera de la red interna y
  saltan el saneamiento.
- **Guardar la foto en PostgreSQL:** simple, pero contradice el SAD (Storage) y
  carga la base de datos.

## Consecuencias
- Se pierde la orientación EXIF: las apps (fases 9 y 10) deben enviar la foto ya
  orientada.
- Puede quedar un objeto huérfano (sin fila que lo referencie) en dos casos: si la
  compensación falla (se registra en el log) o si el proceso se cae entre la subida
  y el fin de la transacción. No afecta a los datos, porque nada lo referencia, y
  una limpieza periódica por clave sin referencia es una mejora pendiente.
- Una foto que se rechaza dentro de la transacción (p. ej. la cuarta de un reporte)
  alcanza a subirse antes de borrarse. Es el precio de no tener la conexión tomada.
- Las fotos pasan por memoria en Reportes y Persistencia (máximo ~6 MB por
  petición): aceptable para el volumen del MVP.
