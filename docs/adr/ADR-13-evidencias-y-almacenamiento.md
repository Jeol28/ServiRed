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
- **Consistencia:** el objeto se sube dentro de la transacción de base de datos;
  si la escritura de la referencia falla, el objeto se borra (compensación). La
  clave del objeto es `reportes/<id>/<idEvidencia>.<ext>` o
  `denuncias/<id>/<idEvidencia>.<ext>` y se guarda también su SHA-256.
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
- Si la transacción falla después de subir el objeto y la compensación también
  falla, puede quedar un objeto huérfano (se registra en el log); una limpieza
  periódica por clave sin referencia es una mejora pendiente.
- Las fotos pasan por memoria en Reportes y Persistencia (máximo ~6 MB por
  petición): aceptable para el volumen del MVP.
