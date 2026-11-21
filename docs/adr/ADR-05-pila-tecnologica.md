# ADR-05 · Pila tecnológica: JEE (Spring) y .NET

**Estado:** aceptada · **Fecha:** 2026-11-14

## Contexto
La Entrega 2 exige implementar con dos ecosistemas empresariales (JEE y .NET).
El SAD (restricciones) pide no acoplar la arquitectura a ellos.

## Decisión
| Servicio | Pila | Motivo |
|---|---|---|
| gateway | Java 21 · Spring Cloud Gateway 2025.0 (WebFlux) | Gateway reactivo maduro con filtros, rate limiting y JWT |
| identidad, reportes, persistencia | Java 21 · Spring Boot 3.5 | Núcleo transaccional; Flyway y JDBC para Supabase/PostgreSQL |
| atencion, indicadores | .NET 10 (LTS) · ASP.NET Core | Servicios con consumidores de eventos y APIs de lectura |
| Mensajería | RabbitMQ 4.1 (exchange topic, colas quorum) | ADR-04 |
| Datos | Supabase (PostgreSQL 17 + Storage); PostgreSQL local en desarrollo | ADR-02 |

Cada servicio aplica arquitectura hexagonal (`dominio`, `aplicacion`,
`infraestructura`). La regla de dependencias se verifica en el build con
ArchUnit (Java) y NetArchTest más referencias de proyecto (.NET).

## Consecuencias
- La integración entre ecosistemas se hace solo por contratos (`/contratos`):
  OpenAPI y eventos JSON. No hay código compartido entre servicios.
- Spring Boot 3.5 se mantiene por requisito del curso aunque ya existe la línea 4.x.
- Java se compila con `release 21` y las imágenes usan `eclipse-temurin:21`.
