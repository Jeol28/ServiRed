using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using ServiRed.Atencion.Infraestructura.RabbitMq;

namespace ServiRed.Atencion.Infraestructura;

public static class ServiciosInfraestructura
{
    public const string EtiquetaReadiness = "ready";

    public static IServiceCollection AgregarInfraestructura(
        this IServiceCollection servicios, IConfiguration configuracion, string nombreServicio)
    {
        servicios.AddSingleton(OpcionesRabbitMq.DesdeConfiguracion(configuracion));
        servicios.AddSingleton(sp => new ConexionRabbitMq(sp.GetRequiredService<OpcionesRabbitMq>(), nombreServicio));
        servicios.AddHealthChecks()
            .AddCheck<ChequeoRabbitMq>("rabbitmq", tags: [EtiquetaReadiness]);
        return servicios;
    }
}
