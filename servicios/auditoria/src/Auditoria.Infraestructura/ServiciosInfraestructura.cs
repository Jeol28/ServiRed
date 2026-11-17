using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;
using ServiRed.Auditoria.Infraestructura.Mensajeria;
using ServiRed.Auditoria.Infraestructura.Persistencia;
using ServiRed.Auditoria.Infraestructura.RabbitMq;

namespace ServiRed.Auditoria.Infraestructura;

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
        servicios.AddHttpClient<IRepositorioAuditoria, ClientePersistencia>(http =>
        {
            http.BaseAddress = persistencia.Url;
            http.Timeout = persistencia.Timeout;
            http.DefaultRequestHeaders.Add(ClientePersistencia.CabeceraClave, persistencia.Clave);
        });
        // El detector guarda ventanas en memoria: una sola instancia por proceso.
        servicios.AddSingleton<DetectorAlertas>();
        servicios.AddSingleton<IMetricasAlertas, MetricasAlertas>();
        servicios.AddScoped<RegistroDeAuditoria>();
        servicios.AddScoped<ConsultasAuditoria>();

        servicios.AddSingleton(OpcionesConsumidor.DesdeConfiguracion(configuracion));
        servicios.AddSingleton<EstadoConsumidor>();
        servicios.AddHostedService<ConsumidorEventos>();

        servicios.AddHealthChecks()
            .AddCheck<ChequeoRabbitMq>("rabbitmq", tags: [EtiquetaReadiness])
            .AddCheck<ChequeoConsumidor>("consumidor", tags: [EtiquetaReadiness]);
        return servicios;
    }
}
