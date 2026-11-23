using Prometheus;
using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Infraestructura;

/// <summary>servired_alertas_seguridad_total{tipo,severidad}: Prometheus la vigila y Alertmanager notifica (RF-13).</summary>
public sealed class MetricasAlertas : IMetricasAlertas
{
    private static readonly Counter Alertas = Metrics.CreateCounter("servired_alertas_seguridad_total",
        "Alertas de seguridad detectadas por Auditoría", new CounterConfiguration { LabelNames = ["tipo", "severidad"] });

    /// <summary>
    /// Las series nacen en 0: Prometheus no detecta con increase() el salto de "no
    /// existe" a 1, así que sin esto la primera alerta de cada tipo pasaría inadvertida.
    /// </summary>
    static MetricasAlertas()
    {
        foreach (var r in new[] { DetectorAlertas.FuerzaBruta, DetectorAlertas.FallosMasivos, DetectorAlertas.TokenReutilizado,
                     DetectorAlertas.ExportacionesInusuales })
            Alertas.WithLabels(r.Tipo, r.Severidad);
    }

    public void Registrar(Alerta alerta) => Alertas.WithLabels(alerta.Tipo, alerta.Severidad).Inc();
}
