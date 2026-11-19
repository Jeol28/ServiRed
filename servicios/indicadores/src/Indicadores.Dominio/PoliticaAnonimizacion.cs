namespace ServiRed.Indicadores.Dominio;

/// <summary>
/// Anonimizador (SAD, componentes de Indicadores; RNF-DAT2; ADR-15). Antes de que
/// un dato salga hacia el público:
/// <list type="bullet">
/// <item>Solo agregados: nunca identificadores de reporte, descripciones ni puntos exactos.</item>
/// <item>k-anonimato: un grupo con menos de <see cref="UmbralMinimo"/> reportes se
/// suprime (no se publica su conteo ni sus estadísticas).</item>
/// <item>La duración solo se publica si al menos <see cref="UmbralMinimo"/> reportes la tienen.</item>
/// <item>El mapa público usa celdas de <see cref="CeldaGrados"/>° (~1,1 km) en las cabeceras
/// municipales. Fuera de ellas (rural disperso y centros poblados), donde una celda de 1 km
/// puede contener solo unas pocas viviendas, usa celdas de <see cref="CeldaRuralGrados"/>°
/// (~5,5 km) y exige <see cref="UmbralRural"/> reportes (ADR-28). Una celda sin clasificar se
/// trata como rural.</item>
/// </list>
/// La exportación regulatoria (personal autorizado) no se suprime, pero redondea
/// las coordenadas a <see cref="DecimalesRegulatorios"/> decimales (~110 m).
/// </summary>
public static class PoliticaAnonimizacion
{
    public const int UmbralMinimo = 3;
    public const double CeldaGrados = 0.01;
    public const double CeldaRuralGrados = 0.05;
    public const int UmbralRural = 5;
    public const int DecimalesRegulatorios = 3;

    public static bool Publicable(Agregado a) => a.Reportes >= UmbralMinimo;

    /// <summary>Celda del mapa público: k = 3 en una cabecera municipal; k = 5 fuera de ella o sin clasificar.</summary>
    public static bool CeldaPublicable(Agregado a) => a.Reportes >= (a.EnCabecera == true ? UmbralMinimo : UmbralRural);

    public static double LadoDeCelda(Agregado a) => a.EnCabecera == true ? CeldaGrados : CeldaRuralGrados;

    public static bool DuracionPublicable(Agregado a) => a.ConDuracion >= UmbralMinimo;

    public static double RedondearRegulatorio(double coordenada) => Math.Round(coordenada, DecimalesRegulatorios);
}
