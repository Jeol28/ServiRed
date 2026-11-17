namespace ServiRed.Indicadores.Dominio;

/// <summary>
/// Anonimizador (SAD, componentes de Indicadores; RNF-DAT2; ADR-15). Antes de que
/// un dato salga hacia el público:
/// <list type="bullet">
/// <item>Solo agregados: nunca identificadores de reporte, descripciones ni puntos exactos.</item>
/// <item>k-anonimato: un grupo con menos de <see cref="UmbralMinimo"/> reportes se
/// suprime (no se publica su conteo ni sus estadísticas).</item>
/// <item>La duración solo se publica si al menos <see cref="UmbralMinimo"/> reportes la tienen.</item>
/// <item>El mapa público usa celdas de <see cref="CeldaGrados"/>° (~1,1 km).</item>
/// </list>
/// La exportación regulatoria (personal autorizado) no se suprime, pero redondea
/// las coordenadas a <see cref="DecimalesRegulatorios"/> decimales (~110 m).
/// </summary>
public static class PoliticaAnonimizacion
{
    public const int UmbralMinimo = 3;
    public const double CeldaGrados = 0.01;
    public const int DecimalesRegulatorios = 3;

    public static bool Publicable(Agregado a) => a.Reportes >= UmbralMinimo;

    public static bool DuracionPublicable(Agregado a) => a.ConDuracion >= UmbralMinimo;

    public static double RedondearRegulatorio(double coordenada) => Math.Round(coordenada, DecimalesRegulatorios);
}
