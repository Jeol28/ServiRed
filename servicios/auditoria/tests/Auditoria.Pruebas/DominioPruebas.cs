using System.Text.Json;
using ServiRed.Auditoria.Dominio;

namespace ServiRed.Auditoria.Pruebas;

public class DominioPruebas
{
    private static readonly DateTimeOffset Ahora = new(2026, 11, 17, 12, 0, 0, TimeSpan.Zero);

    public static SobreEvento Evento(string tipo, string datos, DateTimeOffset? fecha = null, string origen = "reportes") =>
        new(Guid.NewGuid(), tipo, 1, fecha ?? Ahora, Guid.NewGuid(), origen, JsonDocument.Parse(datos).RootElement);

    public static SobreEvento LoginFallido(Guid cuenta, DateTimeOffset fecha) => Evento("accion.auditada", $$"""
        {"categoria":"ACCESO","accion":"INICIO_SESION_FALLIDO","resultado":"FALLO",
         "recurso":{"tipo":"CUENTA","id":"{{cuenta}}"},"detalle":{"motivo":"CLAVE_INCORRECTA"} }
        """, fecha, "identidad");

    [Fact]
    public void ElReporteSeAuditaSinTextoNiUbicacionDelCiudadano()
    {
        var id = Guid.NewGuid();
        var r = Clasificador.Clasificar(Evento("reporte.creado", $$"""
            {"idReporte":"{{id}}","tipoServicio":"GAS","codigoMunicipio":"11001","magnitud":"BARRIO",
             "ubicacion":{"latitud":4.6,"longitud":-74.1},"descripcion":"vivo en la calle 10 #5-20"}
            """))!;

        Assert.Equal(Categorias.Reporte, r.Categoria);
        Assert.Equal(id.ToString(), r.RecursoId);
        Assert.Equal("GAS", r.Detalle["tipoServicio"]);
        Assert.False(r.Detalle.ContainsKey("descripcion"));
        Assert.False(r.Detalle.ContainsKey("ubicacion"));
    }

    [Fact]
    public void LosCambiosDeCasoLlevanAlActor()
    {
        var actor = Guid.NewGuid();
        var r = Clasificador.Clasificar(Evento("caso.actualizado", $$"""
            {"idCaso":"{{Guid.NewGuid()}}","idReporte":"{{Guid.NewGuid()}}","estadoAnterior":"RECIBIDO",
             "estado":"EN_ATENCION","prioridad":"ALTA","idActor":"{{actor}}"}
            """, origen: "atencion"))!;

        Assert.Equal(Categorias.CambioEstado, r.Categoria);
        Assert.Equal(actor, r.ActorId);
        Assert.Equal("EN_ATENCION", r.Detalle["estado"]);
    }

    [Fact]
    public void LaAccionAuditadaSeSaneaEnCualquierProfundidad()
    {
        var r = Clasificador.Clasificar(Evento("accion.auditada", """
            {"categoria":"ADMINISTRACION","accion":"CUENTA_CREADA","resultado":"EXITO",
             "actor":{"idCuenta":"a0000000-0000-4000-8000-000000000009","rol":"ADMINISTRADOR"},
             "recurso":{"tipo":"CUENTA","id":"x"},
             "detalle":{"rol":"OPERADOR","correo":"ana@e.co","extra":{"nombre":"Ana","ip":"1.2.3.4","ok":1}}}
            """, origen: "identidad"))!;

        Assert.Equal("ADMINISTRADOR", r.ActorRol);
        Assert.Equal("OPERADOR", r.Detalle["rol"]);
        Assert.False(r.Detalle.ContainsKey("correo"));
        var extra = (Dictionary<string, object?>)r.Detalle["extra"]!;
        Assert.Equal(["ok"], extra.Keys);
    }

    [Fact]
    public void UnaAccionAuditadaMalFormadaEsInvalidaYLosTiposDesconocidosSeIgnoran()
    {
        Assert.Throws<DatosInvalidos>(() => Clasificador.Clasificar(Evento("accion.auditada", """{"categoria":"INVENTADA","accion":"X"}""")));
        Assert.Throws<DatosInvalidos>(() => Clasificador.Clasificar(Evento("accion.auditada", """{"categoria":"ALERTA","accion":"X"}""")));
        Assert.Null(Clasificador.Clasificar(Evento("otro.evento", "{}")));
    }

    [Fact]
    public void DetectaFuerzaBrutaUnaSolaVezPorVentana()
    {
        var detector = new DetectorAlertas();
        var cuenta = Guid.NewGuid();
        var alertas = Enumerable.Range(0, 7)
            .SelectMany(i => detector.Evaluar(Clasificador.Clasificar(LoginFallido(cuenta, Ahora.AddMinutes(i)))!))
            .ToList();

        var alerta = Assert.Single(alertas);
        Assert.Equal("FUERZA_BRUTA_CUENTA", alerta.Tipo);
        Assert.Equal(5, alerta.Detalle["conteo"]);
    }

    [Fact]
    public void FallosEspaciadosNoAlertan()
    {
        var detector = new DetectorAlertas();
        var cuenta = Guid.NewGuid();

        var alertas = Enumerable.Range(0, 6)
            .SelectMany(i => detector.Evaluar(Clasificador.Clasificar(LoginFallido(cuenta, Ahora.AddMinutes(i * 3)))!));

        Assert.Empty(alertas);
    }

    [Fact]
    public void LaReutilizacionDeTokenAlertaDeInmediato()
    {
        var r = Clasificador.Clasificar(Evento("accion.auditada", """
            {"categoria":"SEGURIDAD","accion":"REFRESCO_REUTILIZADO","resultado":"FALLO","recurso":{"tipo":"SESION"}}
            """, origen: "identidad"))!;

        Assert.Equal("TOKEN_REUTILIZADO", Assert.Single(new DetectorAlertas().Evaluar(r)).Tipo);
    }

    [Fact]
    public void ElGuidDeterministaEsEstable() =>
        Assert.Equal(GuidDeterminista.De("x"), GuidDeterminista.De("x"));
}
