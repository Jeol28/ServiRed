using Microsoft.Extensions.Configuration;

namespace ServiRed.Auditoria.Infraestructura.RabbitMq;

/// <summary>
/// Conexión a RabbitMQ. Usa las mismas variables de entorno que los servicios Java
/// (RABBITMQ_HOST, RABBITMQ_PUERTO, RABBITMQ_USUARIO, RABBITMQ_CLAVE).
/// </summary>
public sealed record OpcionesRabbitMq(string Host, int Puerto, string Usuario, string Clave)
{
    public static OpcionesRabbitMq DesdeConfiguracion(IConfiguration configuracion) => new(
        configuracion["RABBITMQ_HOST"] ?? "localhost",
        int.TryParse(configuracion["RABBITMQ_PUERTO"], out var puerto) ? puerto : 5672,
        configuracion["RABBITMQ_USUARIO"] ?? "guest",
        configuracion["RABBITMQ_CLAVE"] ?? "guest");
}
