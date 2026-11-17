using System.Security.Cryptography;
using System.Text;

namespace ServiRed.Auditoria.Dominio;

/// <summary>UUID derivado de un texto (SHA-256): la misma alerta siempre produce el mismo idEvento (idempotencia).</summary>
public static class GuidDeterminista
{
    public static Guid De(string texto)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(texto))[..16];
        bytes[6] = (byte)((bytes[6] & 0x0F) | 0x50);
        bytes[8] = (byte)((bytes[8] & 0x3F) | 0x80);
        return new Guid(bytes, bigEndian: true);
    }
}
