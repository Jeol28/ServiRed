using NetArchTest.Rules;
using ServiRed.Auditoria.Aplicacion;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Pruebas;

/// <summary>Reglas de la arquitectura hexagonal.</summary>
public class ArquitecturaPruebas
{
    [Fact]
    public void ElDominioNoDependeDeFrameworkNiDeOtrasCapas()
    {
        var resultado = Types.InAssembly(typeof(IdCorrelacion).Assembly)
            .ShouldNot()
            .HaveDependencyOnAny("Microsoft.AspNetCore", "Microsoft.Extensions", "RabbitMQ",
                "ServiRed.Auditoria.Aplicacion", "ServiRed.Auditoria.Infraestructura", "ServiRed.Auditoria.Api")
            .GetResult();

        Assert.True(resultado.IsSuccessful, string.Join(", ", resultado.FailingTypeNames ?? []));
    }

    [Fact]
    public void LaAplicacionNoDependeDeInfraestructura()
    {
        var resultado = Types.InAssembly(typeof(RegistroDeAuditoria).Assembly)
            .ShouldNot()
            .HaveDependencyOnAny("Microsoft.AspNetCore", "RabbitMQ",
                "ServiRed.Auditoria.Infraestructura", "ServiRed.Auditoria.Api")
            .GetResult();

        Assert.True(resultado.IsSuccessful, string.Join(", ", resultado.FailingTypeNames ?? []));
    }
}
