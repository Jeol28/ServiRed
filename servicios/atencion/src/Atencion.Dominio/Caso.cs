using System.Text.RegularExpressions;

namespace ServiRed.Atencion.Dominio;

/// <summary>Entrada de la historia de un caso.</summary>
public sealed record EntradaHistoriaCaso(EstadoCaso Estado, DateTimeOffset Fecha, string Detalle, Guid? Autor);

/// <summary>Referencia a una fotografía del reporte.</summary>
public sealed record EvidenciaCaso(Guid IdEvidencia, string TipoContenido, int TamanoBytes, DateTimeOffset CreadaEn);

/// <summary>
/// Cambios que una decisión aplica a un caso; null = sin cambio. Persistencia los
/// guarda solo si la versión no cambió desde la lectura (concurrencia optimista).
/// </summary>
public sealed record CambiosCaso(
    int VersionEsperada,
    string Detalle,
    Guid? Autor,
    DateTimeOffset Fecha,
    EstadoCaso? Estado = null,
    Prioridad? Prioridad = null,
    bool? PrioridadManual = null,
    Guid? AsignadoA = null,
    Guid? IdEmpresa = null,
    bool? CorteValidado = null,
    string? Resolucion = null,
    DateTimeOffset? ResueltoEn = null,
    DateTimeOffset? CerradoEn = null)
{
    public bool CambiaEstado(EstadoCaso actual) => Estado is not null && Estado != actual;
}

/// <summary>
/// Caso de atención operacional (RF-6, RF-7). Se abre a partir de un reporte y no
/// conoce al ciudadano. Ciclo: RECIBIDO → EN_ATENCION → RESUELTO → CERRADO.
/// </summary>
public sealed partial record Caso(
    Guid IdCaso,
    Guid IdReporte,
    TipoServicio TipoServicio,
    string CodigoMunicipio,
    string? NombreMunicipio,
    Magnitud Magnitud,
    EstadoCaso Estado,
    DateTimeOffset AbiertoEn,
    Guid? IdEmpresa,
    Prioridad Prioridad,
    bool PrioridadManual,
    bool CorteValidado,
    double? Latitud,
    double? Longitud,
    string? Descripcion,
    DateTimeOffset? InicioEstimado,
    Guid? AsignadoA,
    string? Resolucion,
    DateTimeOffset? ResueltoEn,
    DateTimeOffset? CerradoEn,
    int Version,
    IReadOnlyList<EntradaHistoriaCaso> Historia,
    IReadOnlyList<EvidenciaCaso> Evidencias)
{
    public const int LongitudMaximaResolucion = 1000;

    public static Caso AbrirDesdeReporte(Guid idReporte, TipoServicio tipoServicio, string codigoMunicipio,
        Magnitud magnitud, DateTimeOffset ahora, Guid? idEmpresa = null, double? latitud = null,
        double? longitud = null, string? descripcion = null, DateTimeOffset? inicioEstimado = null)
    {
        if (idReporte == Guid.Empty)
            throw new ArgumentException("El reporte es obligatorio", nameof(idReporte));
        if (!CodigoDivipola().IsMatch(codigoMunicipio ?? ""))
            throw new ArgumentException("Código DIVIPOLA inválido", nameof(codigoMunicipio));

        return new Caso(Guid.NewGuid(), idReporte, tipoServicio, codigoMunicipio!, null, magnitud, EstadoCaso.RECIBIDO,
            ahora, idEmpresa, PoliticaPrioridad.Calcular(tipoServicio, magnitud, false), false, false, latitud,
            longitud, descripcion, inicioEstimado, null, null, null, null, 0, [], []);
    }

    public bool EstaCerrado => Estado == EstadoCaso.CERRADO;

    // ------------------------------------------------------------ operador
    /// <summary>El operador empieza a atender: RECIBIDO → EN_ATENCION; si nadie lo tenía, se lo asigna.</summary>
    public CambiosCaso Atender(Actor actor, DateTimeOffset ahora)
    {
        actor.ExigirGestion(IdEmpresa);
        if (Estado != EstadoCaso.RECIBIDO)
            throw new ReglaIncumplida("TRANSICION", $"Solo un caso RECIBIDO pasa a EN_ATENCION (está {Estado})");
        return new CambiosCaso(Version, "En atención", actor.IdCuenta, ahora, Estado: EstadoCaso.EN_ATENCION,
            AsignadoA: AsignadoA ?? (actor.Rol == Rol.OPERADOR ? actor.IdCuenta : null));
    }

    /// <summary>El servicio se restableció: RECIBIDO o EN_ATENCION → RESUELTO, con la resolución.</summary>
    public CambiosCaso Resolver(Actor actor, string? resolucion, DateTimeOffset ahora)
    {
        actor.ExigirGestion(IdEmpresa);
        if (Estado is not (EstadoCaso.RECIBIDO or EstadoCaso.EN_ATENCION))
            throw new ReglaIncumplida("TRANSICION", $"No se puede resolver un caso {Estado}");
        var texto = Texto(resolucion, "resolucion", obligatoria: true);
        return new CambiosCaso(Version, "Resuelto: " + texto, actor.IdCuenta, ahora, Estado: EstadoCaso.RESUELTO,
            Resolucion: texto, ResueltoEn: ahora);
    }

    /// <summary>
    /// Cierre definitivo. Desde RESUELTO basta con cerrarlo; cerrar un caso sin
    /// resolver (duplicado, improcedente) exige explicar el motivo.
    /// </summary>
    public CambiosCaso Cerrar(Actor actor, string? motivo, DateTimeOffset ahora)
    {
        actor.ExigirGestion(IdEmpresa);
        if (EstaCerrado)
            throw new ReglaIncumplida("TRANSICION", "El caso ya está cerrado");
        var texto = Texto(motivo, "resolucion", obligatoria: Estado != EstadoCaso.RESUELTO);
        return new CambiosCaso(Version, texto is null ? "Caso cerrado" : "Caso cerrado: " + texto, actor.IdCuenta,
            ahora, Estado: EstadoCaso.CERRADO, Resolucion: Resolucion is null ? texto : null, CerradoEn: ahora);
    }

    /// <summary>Asignar a un operador de la empresa del caso (la verificación de empresa la hace la aplicación).</summary>
    public CambiosCaso Asignar(Actor actor, Guid idOperador, DateTimeOffset ahora)
    {
        actor.ExigirGestion(IdEmpresa);
        if (EstaCerrado) throw new ReglaIncumplida("CERRADO", "El caso está cerrado");
        if (IdEmpresa is null) throw new ReglaIncumplida("SIN_EMPRESA", "Primero hay que enrutar el caso a una empresa");
        if (AsignadoA == idOperador) throw new ReglaIncumplida("SIN_CAMBIO", "El caso ya está asignado a ese operador");
        return new CambiosCaso(Version, "Asignado a un operador", actor.IdCuenta, ahora, AsignadoA: idOperador);
    }

    public CambiosCaso FijarPrioridad(Actor actor, Prioridad prioridad, DateTimeOffset ahora)
    {
        actor.ExigirGestion(IdEmpresa);
        if (EstaCerrado) throw new ReglaIncumplida("CERRADO", "El caso está cerrado");
        return new CambiosCaso(Version, $"Prioridad fijada en {prioridad}", actor.IdCuenta, ahora,
            Prioridad: prioridad, PrioridadManual: true);
    }

    /// <summary>
    /// Solo un administrador enruta, y solo a la empresa dueña de la zona del caso (ADR-35): sirve
    /// para casos que quedaron sin empresa o con otra (p. ej. si cambió la cobertura).
    /// </summary>
    public CambiosCaso Enrutar(Actor actor, Guid idEmpresa, Cobertura? zona, DateTimeOffset ahora)
    {
        if (actor.Rol != Rol.ADMINISTRADOR) throw new AccesoDenegado("Solo un administrador enruta casos");
        if (EstaCerrado) throw new ReglaIncumplida("CERRADO", "El caso está cerrado");
        if (zona?.IdEmpresa is not { } duena)
            throw new ReglaIncumplida("SIN_EMPRESA_EN_ZONA",
                $"Ninguna empresa presta este servicio en {zona?.NombreZona ?? "la zona del caso"}");
        if (idEmpresa != duena)
            throw new ReglaIncumplida("FUERA_DE_ZONA",
                $"Solo la empresa dueña de {zona.NombreZona} ({zona.NombreEmpresa}) puede gestionar el caso");
        if (IdEmpresa == idEmpresa) throw new ReglaIncumplida("SIN_CAMBIO", "El caso ya es de esa empresa");
        return new CambiosCaso(Version, $"Enrutado a la empresa dueña de {zona.NombreZona}", actor.IdCuenta, ahora,
            IdEmpresa: idEmpresa);
    }

    // ------------------------------------------------------------ eventos de Reportes
    /// <summary>El ciudadano registró el restablecimiento: el caso abierto pasa a RESUELTO. Null si no aplica.</summary>
    public CambiosCaso? ResolverPorRestablecimiento(int duracionMinutos, DateTimeOffset ahora) =>
        Estado is EstadoCaso.RECIBIDO or EstadoCaso.EN_ATENCION
            ? new CambiosCaso(Version, $"El ciudadano registró el restablecimiento ({duracionMinutos} min)", null, ahora,
                Estado: EstadoCaso.RESUELTO, Resolucion: "Restablecimiento reportado por el ciudadano", ResueltoEn: ahora)
            : null;

    /// <summary>La comunidad validó (o dejó de validar) el corte: se recalcula la prioridad si no es manual.</summary>
    public CambiosCaso? RegistrarValidacionDelCorte(bool validado, DateTimeOffset ahora)
    {
        if (EstaCerrado || validado == CorteValidado) return null;
        var nueva = PrioridadManual ? (Prioridad?)null : PoliticaPrioridad.Calcular(TipoServicio, Magnitud, validado);
        return new CambiosCaso(Version,
            validado ? "La comunidad validó el corte" : "El corte dejó de estar validado", null, ahora,
            CorteValidado: validado, Prioridad: nueva == Prioridad ? null : nueva);
    }

    private static string? Texto(string? valor, string campo, bool obligatoria)
    {
        var texto = string.IsNullOrWhiteSpace(valor) ? null : valor.Trim();
        if (texto is null && obligatoria) throw new DatosInvalidos(campo, "es obligatoria");
        if (texto is { Length: > LongitudMaximaResolucion })
            throw new DatosInvalidos(campo, $"máximo {LongitudMaximaResolucion} caracteres");
        return texto;
    }

    [GeneratedRegex("^[0-9]{5}$")]
    private static partial Regex CodigoDivipola();
}
