namespace ServiRed.Atencion.Aplicacion;

/// <summary>
/// El mensaje no podrá procesarse nunca (datos inválidos o rechazados por una
/// regla de negocio). No se reintenta: va directo a la cola de mensajes fallidos.
/// </summary>
public sealed class FallaPermanente(string mensaje, Exception? causa = null) : Exception(mensaje, causa);
