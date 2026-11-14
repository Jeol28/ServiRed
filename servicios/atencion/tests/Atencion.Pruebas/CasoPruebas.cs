using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Pruebas;

public class CasoPruebas
{
    private static readonly DateTimeOffset Ahora = new(2026, 11, 14, 15, 0, 0, TimeSpan.Zero);

    [Fact]
    public void UnCasoNuevoQuedaRecibido()
    {
        var idReporte = Guid.NewGuid();

        var caso = Caso.AbrirDesdeReporte(idReporte, TipoServicio.ENERGIA, "11001", Magnitud.CUADRA, Ahora);

        Assert.Equal(EstadoCaso.RECIBIDO, caso.Estado);
        Assert.Equal(idReporte, caso.IdReporte);
        Assert.NotEqual(Guid.Empty, caso.IdCaso);
        Assert.Equal(Ahora, caso.AbiertoEn);
    }

    [Theory]
    [InlineData("1100")]
    [InlineData("ABCDE")]
    [InlineData("")]
    public void RechazaCodigosDivipolaInvalidos(string codigo)
    {
        Assert.Throws<ArgumentException>(() =>
            Caso.AbrirDesdeReporte(Guid.NewGuid(), TipoServicio.GAS, codigo, Magnitud.BARRIO, Ahora));
    }

    [Fact]
    public void ExigeElReporte()
    {
        Assert.Throws<ArgumentException>(() =>
            Caso.AbrirDesdeReporte(Guid.Empty, TipoServicio.GAS, "11001", Magnitud.BARRIO, Ahora));
    }
}
