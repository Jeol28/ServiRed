# PostgreSQL 17 con PostGIS 3 para el entorno local y las pruebas (ADR-27).
# La imagen oficial postgis/postgis solo se publica para amd64; esta se construye sobre la
# imagen oficial de PostgreSQL con el paquete de PostGIS del repositorio de PostgreSQL
# (PGDG), igual que aquella, y funciona en amd64 y arm64. En Supabase PostGIS ya existe.
FROM postgres:17-bookworm
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-17-postgis-3 \
 && rm -rf /var/lib/apt/lists/*
