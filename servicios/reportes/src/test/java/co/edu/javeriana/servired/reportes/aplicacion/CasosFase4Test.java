package co.edu.javeriana.servired.reportes.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ImagenSaneada;
import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.ObjetoValidacion;
import co.edu.javeriana.servired.reportes.dominio.ReglaIncumplida;
import co.edu.javeriana.servired.reportes.dominio.ReglasValidacion;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.ReporteNoEncontrado;
import co.edu.javeriana.servired.reportes.dominio.SentidoValidacion;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CasosFase4Test {

    private static final Ubicacion LUGAR = new Ubicacion(4.6287, -74.0648);
    private final PersistenciaEnMemoria persistencia = new PersistenciaEnMemoria();
    private final Clock reloj = Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"), ZoneOffset.UTC);
    private final ParticipacionCiudadana participacion =
            new ParticipacionCiudadana(persistencia, new ReglasValidacion(3, 1000), reloj);
    private final GestionEvidencias evidencias = new GestionEvidencias(persistencia,
            b -> new ImagenSaneada("image/png", b, 1, 1));
    private final RecibirDenuncia denuncias = new RecibirDenuncia(persistencia, persistencia,
            b -> new ImagenSaneada("image/jpeg", b, 1, 1), reloj);
    private final UUID autor = UUID.randomUUID();
    private Reporte reporte;

    @BeforeEach
    void crear() {
        reporte = new CrearReporte(persistencia, persistencia, reloj).ejecutar(autor, new CrearReporte.Comando(
                TipoServicio.ENERGIA, LUGAR, Instant.parse("2026-11-16T12:00:00Z"), Magnitud.CUADRA, null),
                IdCorrelacion.nuevo());
    }

    @Test
    void restablecerEmiteElEventoConLaDuracion() {
        var r = participacion.restablecer(autor, reporte.idReporte(), Instant.parse("2026-11-16T14:30:00Z"), null,
                IdCorrelacion.nuevo());

        assertThat(r.estado()).isEqualTo(EstadoReporte.RESTABLECIDO);
        assertThat(persistencia.ultimoEvento.tipo()).isEqualTo("restablecimiento.registrado");
        assertThat(persistencia.ultimoEvento.datos()).containsEntry("duracionMinutos", 150);
    }

    @Test
    void validarEsIdempotenteParaElMismoCiudadano() {
        UUID vecina = UUID.randomUUID();

        var primero = participacion.validar(vecina, reporte.idReporte(), ObjetoValidacion.CORTE,
                SentidoValidacion.CONFIRMA, LUGAR, IdCorrelacion.nuevo());
        var segundo = participacion.validar(vecina, reporte.idReporte(), ObjetoValidacion.CORTE,
                SentidoValidacion.CONTROVIERTE, LUGAR, IdCorrelacion.nuevo());

        assertThat(primero.nuevo()).isTrue();
        assertThat(segundo.nuevo()).isFalse();
        assertThat(segundo.conteo().confirmaciones()).isEqualTo(1);
        assertThat(segundo.conteo().controversias()).isZero();
    }

    @Test
    void recalculaSiOtroVotoEntraEnMedioYElEventoRefleaElTotalReal() {
        persistencia.conflictosDeVersion = 1;
        participacion.validar(UUID.randomUUID(), reporte.idReporte(), ObjetoValidacion.CORTE,
                SentidoValidacion.CONFIRMA, LUGAR, IdCorrelacion.nuevo());

        var tercero = participacion.validar(UUID.randomUUID(), reporte.idReporte(), ObjetoValidacion.CORTE,
                SentidoValidacion.CONFIRMA, LUGAR, IdCorrelacion.nuevo());

        assertThat(tercero.conteo().netas()).isEqualTo(3);
        assertThat(tercero.validado()).isTrue();
        assertThat(persistencia.ultimoEvento.datos()).containsEntry("confirmacionesNetas", 3)
                .containsEntry("validado", true);
        assertThat(persistencia.ultimoEvento.datos().toString()).doesNotContain(autor.toString());
    }

    @Test
    void soloElAutorAdjuntaHastaTresFotos() {
        assertThatThrownBy(() -> evidencias.adjuntar(UUID.randomUUID(), reporte.idReporte(), new byte[]{1},
                IdCorrelacion.nuevo())).isInstanceOf(ReporteNoEncontrado.class);
        for (int i = 0; i < 3; i++) {
            evidencias.adjuntar(autor, reporte.idReporte(), new byte[]{1, 2}, IdCorrelacion.nuevo());
        }
        assertThatThrownBy(() -> evidencias.adjuntar(autor, reporte.idReporte(), new byte[]{1}, IdCorrelacion.nuevo()))
                .isInstanceOfSatisfying(ReglaIncumplida.class, e -> assertThat(e.motivo()).isEqualTo("LIMITE_EVIDENCIAS"));
    }

    @Test
    void laDenunciaEmiteDenunciaRecibidaSinIdentificadores() {
        var r = denuncias.ejecutar(TipoServicio.GAS, LUGAR, "Acometida de gas sin medidor", new byte[]{9},
                IdCorrelacion.nuevo());

        assertThat(r.tieneEvidencia()).isTrue();
        var evento = persistencia.ultimoEvento;
        assertThat(evento.tipo()).isEqualTo("denuncia.recibida");
        assertThat(evento.datos()).containsOnlyKeys("idDenuncia", "tipoServicio", "codigoMunicipio", "ubicacion",
                "fechaRecepcion", "tieneEvidencia");
    }
}
