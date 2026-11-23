using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Infraestructura.Mensajeria;
using ServiRed.Indicadores.Infraestructura.Persistencia;
using ServiRed.Indicadores.Infraestructura.RabbitMq;

namespace ServiRed.Indicadores.Infraestructura;

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
        servicios.AddHttpClient<ClienteVistaLectura>(http =>
        {
            http.BaseAddress = persistencia.Url;
            http.DefaultRequestHeaders.Add(ClienteVistaLectura.CabeceraClave, persistencia.Clave);
        }).ConCircuitoPersistencia(persistencia.Timeout);
        servicios.AddTransient<IVistaLectura>(sp => sp.GetRequiredService<ClienteVistaLectura>());
        servicios.AddTransient<IRegistroAcciones>(sp => sp.GetRequiredService<ClienteVistaLectura>());
        servicios.AddScoped<ProyeccionEventos>();
        servicios.AddScoped<ConsultasIndicadores>();

        servicios.AddSingleton(OpcionesConsumidor.DesdeConfiguracion(configuracion));
        servicios.AddSingleton<EstadoConsumidor>();
        servicios.AddHostedService<ConsumidorEventos>();

        servicios.AddHealthChecks()
            .AddCheck<ChequeoRabbitMq>("rabbitmq", tags: [EtiquetaReadiness])
            .AddCheck<ChequeoConsumidor>("consumidor", tags: [EtiquetaReadiness]);
        return servicios;
    }
}
