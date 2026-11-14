using NetArchTest.Rules;
using ServiRed.Atencion.Aplicacion;
using ServiRed.Atencion.Dominio;

namespace ServiRed.Atencion.Pruebas;

/// <summary>Reglas de la arquitectura hexagonal.</summary>
public class ArquitecturaPruebas
{
    [Fact]
    public void ElDominioNoDependeDeFrameworkNiDeOtrasCapas()
    {
        var resultado = Types.InAssembly(typeof(IdCorrelacion).Assembly)
            .ShouldNot()
            .HaveDependencyOnAny("Microsoft.AspNetCore", "Microsoft.Extensions", "RabbitMQ",
                "ServiRed.Atencion.Aplicacion", "ServiRed.Atencion.Infraestructura", "ServiRed.Atencion.Api")
            .GetResult();

        Assert.True(resultado.IsSuccessful, string.Join(", ", resultado.FailingTypeNames ?? []));
    }

    [Fact]
    public void LaAplicacionNoDependeDeInfraestructura()
    {
        var resultado = Types.InAssembly(typeof(AbrirCasoDesdeReporte).Assembly)
            .ShouldNot()
            .HaveDependencyOnAny("Microsoft.AspNetCore", "RabbitMQ",
                "ServiRed.Atencion.Infraestructura", "ServiRed.Atencion.Api")
            .GetResult();

        Assert.True(resultado.IsSuccessful, string.Join(", ", resultado.FailingTypeNames ?? []));
    }
}
