namespace ServiRed.Atencion.Dominio;

/// <summary>La operación viola una regla del ciclo de vida del caso o de la moderación (409).</summary>
public sealed class ReglaIncumplida(string motivo, string mensaje) : Exception(mensaje)
{
    public string Motivo { get; } = motivo;
}

/// <summary>El actor no tiene permiso sobre ese caso o denuncia (403).</summary>
public sealed class AccesoDenegado(string mensaje) : Exception(mensaje);

/// <summary>El caso o la denuncia no existe o no es visible para el actor (404).</summary>
public sealed class NoEncontrado(string mensaje) : Exception(mensaje);

/// <summary>Datos de entrada inválidos (400).</summary>
public sealed class DatosInvalidos(string campo, string mensaje) : Exception(mensaje)
{
    public string Campo { get; } = campo;
}
