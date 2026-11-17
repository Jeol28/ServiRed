namespace ServiRed.Atencion.Dominio;

/// <summary>
/// Denuncia anónima en moderación (RF-5). Atención trabaja solo con el contenido
/// y la evidencia: no hay forma de contactar al denunciante (ADR-09).
/// </summary>
public sealed record Moderacion(
    Guid IdDenuncia,
    TipoServicio TipoServicio,
    string CodigoMunicipio,
    string? NombreMunicipio,
    double Latitud,
    double Longitud,
    string Descripcion,
    DateTimeOffset RecibidaEn,
    Guid? IdEmpresa,
    EstadoModeracion Estado,
    string? Nota,
    Guid? ModeradaPor,
    DateTimeOffset? ModeradaEn,
    int Version,
    IReadOnlyList<Guid> Evidencias)
{
    public Decision Decidir(Actor actor, EstadoModeracion decision, string? nota, DateTimeOffset ahora)
    {
        actor.ExigirGestion(IdEmpresa);
        if (Estado != EstadoModeracion.PENDIENTE)
            throw new ReglaIncumplida("YA_MODERADA", "La denuncia ya fue moderada");
        if (decision == EstadoModeracion.PENDIENTE)
            throw new DatosInvalidos("decision", "debe ser VALIDADA o DESCARTADA");
        var texto = nota?.Trim();
        if (texto is null || texto.Length < 5 || texto.Length > 1000)
            throw new DatosInvalidos("nota", "entre 5 y 1000 caracteres");
        return new Decision(Version, decision, texto, actor.IdCuenta, ahora);
    }
}

public sealed record Decision(int VersionEsperada, EstadoModeracion Estado, string Nota, Guid ModeradaPor,
    DateTimeOffset Fecha);
