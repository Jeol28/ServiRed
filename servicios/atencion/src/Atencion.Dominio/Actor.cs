namespace ServiRed.Atencion.Dominio;

/// <summary>
/// Usuario del personal que actúa (tomado del JWT ya validado). El operador está
/// atado a su empresa; el regulador solo consulta; el administrador gestiona todo.
/// </summary>
public sealed record Actor(Guid IdCuenta, Rol Rol, Guid? IdEmpresa)
{
    public bool PuedeVer(Guid? idEmpresaDelRecurso) => Rol switch
    {
        Rol.ADMINISTRADOR or Rol.REGULADOR => true,
        Rol.OPERADOR => IdEmpresa is not null && IdEmpresa == idEmpresaDelRecurso,
        _ => false,
    };

    public void ExigirGestion(Guid? idEmpresaDelRecurso)
    {
        if (Rol == Rol.ADMINISTRADOR) return;
        if (Rol == Rol.OPERADOR && IdEmpresa is not null && IdEmpresa == idEmpresaDelRecurso) return;
        throw new AccesoDenegado(Rol == Rol.REGULADOR
            ? "El regulador solo consulta"
            : "El caso pertenece a otra empresa");
    }

    /// <summary>Empresa a la que se restringe una bandeja: la propia para el operador, ninguna para los demás.</summary>
    public Guid? EmpresaForzada => Rol == Rol.OPERADOR ? IdEmpresa ?? Guid.Empty : null;
}
