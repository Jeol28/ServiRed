namespace ServiRed.Atencion.Dominio;

/// <summary>
/// Priorización automática (RF-7, ADR-14). Puntaje = magnitud (VIVIENDA 1,
/// CUADRA 2, BARRIO 3, SECTOR 4) + 1 si la comunidad validó el corte + 1 si es
/// gas (riesgo para la seguridad). 1 → BAJA, 2–3 → MEDIA, 4 → ALTA, 5+ → CRITICA.
/// Un operador puede fijarla a mano; desde entonces el cálculo no la cambia.
/// </summary>
public static class PoliticaPrioridad
{
    public static int Puntaje(TipoServicio servicio, Magnitud magnitud, bool corteValidado) =>
        (int)magnitud + 1 + (corteValidado ? 1 : 0) + (servicio == TipoServicio.GAS ? 1 : 0);

    public static Prioridad Calcular(TipoServicio servicio, Magnitud magnitud, bool corteValidado) =>
        Puntaje(servicio, magnitud, corteValidado) switch
        {
            <= 1 => Prioridad.BAJA,
            <= 3 => Prioridad.MEDIA,
            4 => Prioridad.ALTA,
            _ => Prioridad.CRITICA,
        };
}
