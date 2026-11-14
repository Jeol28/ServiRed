using NetArchTest.Rules;
using ServiRed.Indicadores.Aplicacion;
using ServiRed.Indicadores.Dominio;

namespace ServiRed.Indicadores.Pruebas;

/// <summary>Reglas de la arquitectura hexagonal.</summary>
public class ArquitecturaPruebas
{
    [Fact]
    public void ElDominioNoDependeDeFrameworkNiDeOtrasCapas()
    {
        var resultado = Types.InAssembly(typeof(IdCorrelacion).Assembly)
            .ShouldNot()
            .HaveDependencyOnAny("Microsoft.AspNetCore", "Microsoft.Extensions", "RabbitMQ",
                "ServiRed.Indicadores.Aplicacion", "ServiRed.Indicadores.Infraestructura", "ServiRed.Indicadores.Api")
            .GetResult();

        Assert.True(resultado.IsSuccessful, string.Join(", ", resultado.FailingTypeNames ?? []));
    }

    [Fact]
    public void LaAplicacionNoDependeDeInfraestructura()
    {
        var resultado = Types.InAssembly(typeof(MarcadorAplicacion).Assembly)
            .ShouldNot()
            .HaveDependencyOnAny("Microsoft.AspNetCore", "RabbitMQ",
                "ServiRed.Indicadores.Infraestructura", "ServiRed.Indicadores.Api")
            .GetResult();

        Assert.True(resultado.IsSuccessful, string.Join(", ", resultado.FailingTypeNames ?? []));
    }
}
