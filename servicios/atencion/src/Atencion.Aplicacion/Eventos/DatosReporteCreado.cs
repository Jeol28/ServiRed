namespace ServiRed.Atencion.Aplicacion.Eventos;

public sealed record UbicacionEvento(double Latitud, double Longitud);

/// <summary>Datos de reporte.creado v1 que Atención necesita.</summary>
public sealed record DatosReporteCreado(
    Guid IdReporte,
    string TipoServicio,
    string CodigoMunicipio,
    string Magnitud,
    DateTimeOffset FechaRegistro,
    UbicacionEvento? Ubicacion = null,
    string? Descripcion = null,
    DateTimeOffset? InicioEstimado = null);

/// <summary>Datos de restablecimiento.registrado v1.</summary>
public sealed record DatosRestablecimiento(Guid IdReporte, DateTimeOffset FechaRestablecimiento, int DuracionMinutos);

/// <summary>Datos de validacion.registrada v1.</summary>
public sealed record DatosValidacion(Guid IdReporte, string Objeto, string Sentido, int ConfirmacionesNetas,
    bool Validado);

/// <summary>Datos de denuncia.recibida v1 (sin ningún identificador del denunciante).</summary>
public sealed record DatosDenuncia(Guid IdDenuncia, string TipoServicio, string CodigoMunicipio,
    DateTimeOffset FechaRecepcion, bool TieneEvidencia = false);
