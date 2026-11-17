namespace ServiRed.Indicadores.Dominio;

public enum TipoServicio { ENERGIA, GAS, ACUEDUCTO, ALCANTARILLADO, ASEO }

public enum Magnitud { VIVIENDA, CUADRA, BARRIO, SECTOR }

/// <summary>ACTIVO: sin restablecer ni resolver. RESUELTO: restablecido o resuelto por el operador.</summary>
public enum Situacion { ACTIVO, RESUELTO }

public enum NivelTerritorial { DEPARTAMENTO, MUNICIPIO }

public enum Rol { CIUDADANO, OPERADOR, REGULADOR, ADMINISTRADOR }

public sealed class DatosInvalidos(string campo, string mensaje) : Exception(mensaje)
{
    public string Campo { get; } = campo;
}

public sealed class AccesoDenegado(string mensaje) : Exception(mensaje);

/// <summary>Usuario del personal (JWT validado). El operador solo ve lo de su empresa.</summary>
public sealed record Actor(Guid IdCuenta, Rol Rol, Guid? IdEmpresa)
{
    public Guid? EmpresaForzada => Rol == Rol.OPERADOR ? IdEmpresa ?? Guid.Empty : null;
}
