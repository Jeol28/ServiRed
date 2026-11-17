namespace ServiRed.Atencion.Dominio;

/// <summary>
/// Enrutamiento por cobertura (RF-6, ADR-08): el caso va a la empresa que presta
/// ese servicio en ese municipio. Si varias lo cubren se elige la de menor
/// identificador (determinista) y un administrador puede reenrutarlo. Si
/// ninguna lo cubre queda sin empresa, visible para administradores y reguladores.
/// </summary>
public static class Enrutador
{
    public static Guid? Elegir(IEnumerable<Guid> empresasConCobertura) =>
        empresasConCobertura.Order().Cast<Guid?>().FirstOrDefault();
}
