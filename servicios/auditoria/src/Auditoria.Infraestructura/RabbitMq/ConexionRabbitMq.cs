using RabbitMQ.Client;

namespace ServiRed.Auditoria.Infraestructura.RabbitMq;

/// <summary>
/// Conexión única y compartida a RabbitMQ, creada de forma perezosa. Si el broker
/// no está disponible se reintenta en la siguiente solicitud: el servicio arranca
/// igual y su readiness refleja el estado real.
/// </summary>
public sealed class ConexionRabbitMq(OpcionesRabbitMq opciones, string nombreServicio) : IAsyncDisposable
{
    private readonly SemaphoreSlim _candado = new(1, 1);
    private IConnection? _conexion;

    public async Task<IConnection> ObtenerAsync(CancellationToken ct = default)
    {
        if (_conexion is { IsOpen: true }) return _conexion;

        await _candado.WaitAsync(ct);
        try
        {
            if (_conexion is { IsOpen: true }) return _conexion;
            if (_conexion is not null) await _conexion.DisposeAsync();

            var fabrica = new ConnectionFactory
            {
                HostName = opciones.Host,
                Port = opciones.Puerto,
                UserName = opciones.Usuario,
                Password = opciones.Clave,
                ClientProvidedName = nombreServicio,
                AutomaticRecoveryEnabled = true,
            };
            _conexion = await fabrica.CreateConnectionAsync(ct);
            return _conexion;
        }
        finally
        {
            _candado.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        if (_conexion is not null) await _conexion.DisposeAsync();
        _candado.Dispose();
    }
}
