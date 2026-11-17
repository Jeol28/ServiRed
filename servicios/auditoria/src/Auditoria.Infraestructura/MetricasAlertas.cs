using Prometheus;
using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Infraestructura;

/// <summary>servired_alertas_seguridad_total{tipo,severidad}: Prometheus la vigila y Alertmanager notifica (RF-13).</summary>
public sealed class MetricasAlertas : IMetricasAlertas
{
    private static readonly Counter Alertas = Metrics.CreateCounter("servired_alertas_seguridad_total",
        "Alertas de seguridad detectadas por Auditoría", new CounterConfiguration { LabelNames = ["tipo", "severidad"] });

    public void Registrar(Alerta alerta) => Alertas.WithLabels(alerta.Tipo, alerta.Severidad).Inc();
}
