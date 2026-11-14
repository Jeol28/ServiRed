using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Pruebas;

public class IdCorrelacionPruebas
{
    [Fact]
    public void ConservaUnUuidValido()
    {
        var enviado = Guid.NewGuid().ToString();

        Assert.Equal(enviado, IdCorrelacion.DesdeCabecera(enviado).Valor);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("abc")]
    [InlineData("<script>alert(1)</script>")]
    [InlineData("usuario@correo.com")]
    public void GeneraUnoNuevoSiFaltaOEsInvalido(string? enviado)
    {
        var resultado = IdCorrelacion.DesdeCabecera(enviado).Valor;

        Assert.NotEqual(enviado, resultado);
        Assert.True(Guid.TryParse(resultado, out _));
    }
}
