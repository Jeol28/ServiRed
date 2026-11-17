using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Aplicacion.Puertos;
using ServiRed.Atencion.Infraestructura.Mensajeria;
using ServiRed.Atencion.Infraestructura.Persistencia;
using ServiRed.Atencion.Infraestructura.RabbitMq;

namespace ServiRed.Atencion.Infraestructura;

public static class ServiciosInfraestructura
{
    public const string EtiquetaReadiness = "ready";

    public static IServiceCollection AgregarInfraestructura(
        this IServiceCollection servicios, IConfiguration configuracion, string nombreServicio)
    {
        servicios.AddSingleton(TimeProvider.System);

        servicios.AddSingleton(OpcionesRabbitMq.DesdeConfiguracion(configuracion));
        servicios.AddSingleton(sp => new ConexionRabbitMq(sp.GetRequiredService<OpcionesRabbitMq>(), nombreServicio));

        var persistencia = OpcionesPersistencia.DesdeConfiguracion(configuracion);
        servicios.AddHttpClient<ClientePersistencia>(http =>
        {
            http.BaseAddress = persistencia.Url;
            http.Timeout = persistencia.Timeout;
            http.DefaultRequestHeaders.Add(ClientePersistencia.CabeceraClave, persistencia.Clave);
        });
        servicios.AddTransient<IRepositorioCasos>(sp => sp.GetRequiredService<ClientePersistencia>());
        servicios.AddTransient<ICatalogoAtencion>(sp => sp.GetRequiredService<ClientePersistencia>());
        servicios.AddTransient<IRepositorioModeracion>(sp => sp.GetRequiredService<ClientePersistencia>());
        servicios.AddScoped<ReaccionesAEventos>();
        servicios.AddScoped<GestionCasos>();
        servicios.AddScoped<GestionModeracion>();

        servicios.AddSingleton(OpcionesConsumidor.DesdeConfiguracion(configuracion));
        servicios.AddSingleton<EstadoConsumidor>();
        servicios.AddHostedService<ConsumidorEventosReportes>();

        servicios.AddHealthChecks()
            .AddCheck<ChequeoRabbitMq>("rabbitmq", tags: [EtiquetaReadiness])
            .AddCheck<ChequeoConsumidor>("consumidor", tags: [EtiquetaReadiness]);
        return servicios;
    }
}
