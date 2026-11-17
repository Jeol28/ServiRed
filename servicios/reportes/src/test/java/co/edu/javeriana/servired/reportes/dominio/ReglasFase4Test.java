package co.edu.javeriana.servired.reportes.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReglasFase4Test {

    private static final Instant AHORA = Instant.parse("2026-11-16T15:00:00Z");
    private static final Instant INICIO = Instant.parse("2026-11-16T10:00:00Z");
    private static final Ubicacion CENTRO = new Ubicacion(4.6287, -74.0648);
    private final UUID autor = UUID.randomUUID();
    private final ReglasValidacion reglas = new ReglasValidacion(3, 1000);

    private Reporte reporte(EstadoReporte estado, Reporte.Conteo corte) {
        return new Reporte(UUID.randomUUID(), autor, TipoServicio.ENERGIA, CENTRO, new Municipio("11001", "BOGOTÁ"),
                INICIO, Magnitud.CUADRA, null, estado, INICIO, null, null, 0, corte, null, List.of(), List.of());
    }

    @Test
    void laDistanciaDelHaversineEsCorrecta() {
        // ~1,11 km por cada 0,01° de latitud
        assertThat(CENTRO.distanciaMetros(new Ubicacion(4.6387, -74.0648))).isBetween(1100.0, 1125.0);
        assertThat(CENTRO.distanciaMetros(CENTRO)).isZero();
    }

    @Test
    void elRestablecimientoCalculaLaDuracionEnMinutos() {
        var r = reporte(EstadoReporte.RECIBIDO, null).restablecer(autor, INICIO.plus(Duration.ofMinutes(185)), " ok ", AHORA);

        assertThat(r.duracionMinutos()).isEqualTo(185);
        assertThat(r.comentario()).isEqualTo("ok");
    }

    @Test
    void elRestablecimientoDebeSerPosteriorAlInicioYNoFuturo() {
        var r = reporte(EstadoReporte.EN_ATENCION, null);
        assertThatThrownBy(() -> r.restablecer(autor, INICIO.minusSeconds(60), null, AHORA)).isInstanceOf(ReporteInvalido.class);
        assertThatThrownBy(() -> r.restablecer(autor, AHORA.plus(Duration.ofHours(1)), null, AHORA))
                .isInstanceOf(ReporteInvalido.class);
        assertThat(r.restablecer(autor, AHORA.plusSeconds(120), null, AHORA).fecha()).isEqualTo(AHORA);
    }

    @Test
    void soloElAutorRestableceYUnaSolaVez() {
        assertThatThrownBy(() -> reporte(EstadoReporte.RECIBIDO, null).restablecer(UUID.randomUUID(), AHORA, null, AHORA))
                .isInstanceOf(ReporteNoEncontrado.class);
        assertThatThrownBy(() -> reporte(EstadoReporte.CERRADO, null).restablecer(autor, AHORA, null, AHORA))
                .isInstanceOfSatisfying(ReglaIncumplida.class, e -> assertThat(e.motivo()).isEqualTo("YA_RESTABLECIDO"));
    }

    @Test
    void validaConTresConfirmacionesNetas() {
        var dos = reporte(EstadoReporte.RECIBIDO, new Reporte.Conteo(3, 1));

        var resultado = reglas.evaluar(dos, UUID.randomUUID(), ObjetoValidacion.CORTE, SentidoValidacion.CONFIRMA,
                new Ubicacion(4.6300, -74.0650));

        assertThat(resultado.conteo().netas()).isEqualTo(3);
        assertThat(resultado.validado()).isTrue();
        assertThat(reglas.evaluar(dos, UUID.randomUUID(), ObjetoValidacion.CORTE, SentidoValidacion.CONTROVIERTE,
                CENTRO).validado()).isFalse();
    }

    @Test
    void elAutorNoValidaYElValidadorDebeEstarCerca() {
        var r = reporte(EstadoReporte.RECIBIDO, null);
        assertThatThrownBy(() -> reglas.evaluar(r, autor, ObjetoValidacion.CORTE, SentidoValidacion.CONFIRMA, CENTRO))
                .isInstanceOfSatisfying(ReglaIncumplida.class, e -> assertThat(e.motivo()).isEqualTo("AUTOR"));
        assertThatThrownBy(() -> reglas.evaluar(r, UUID.randomUUID(), ObjetoValidacion.CORTE, SentidoValidacion.CONFIRMA,
                new Ubicacion(4.6400, -74.0648)))
                .isInstanceOfSatisfying(ReglaIncumplida.class, e -> assertThat(e.motivo()).isEqualTo("LEJOS"));
    }

    @Test
    void elRestablecimientoSoloSeValidaSiExiste() {
        assertThatThrownBy(() -> reglas.evaluar(reporte(EstadoReporte.RECIBIDO, null), UUID.randomUUID(),
                ObjetoValidacion.RESTABLECIMIENTO, SentidoValidacion.CONFIRMA, CENTRO))
                .isInstanceOfSatisfying(ReglaIncumplida.class, e -> assertThat(e.motivo()).isEqualTo("SIN_RESTABLECIMIENTO"));
        assertThatThrownBy(() -> reglas.evaluar(reporte(EstadoReporte.CERRADO, null), UUID.randomUUID(),
                ObjetoValidacion.CORTE, SentidoValidacion.CONFIRMA, CENTRO)).isInstanceOf(ReglaIncumplida.class);
    }

    @Test
    void laPoliticaDeEvidenciaReconoceElTipoPorSuFirma() {
        assertThat(PoliticaEvidencia.tipoPorFirma(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo("image/jpeg");
        assertThat(PoliticaEvidencia.tipoPorFirma(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .isEqualTo("image/png");
        assertThat(PoliticaEvidencia.tipoPorFirma("<?php echo 1; ?>".getBytes())).isNull();
        assertThatThrownBy(() -> PoliticaEvidencia.validarTamano(6L * 1024 * 1024)).isInstanceOf(EvidenciaInvalida.class);
        assertThatThrownBy(() -> PoliticaEvidencia.validarDimensiones(20_000, 20_000)).isInstanceOf(EvidenciaInvalida.class);
    }

    @Test
    void laDenunciaNoTieneDatosDelDenuncianteYValidaSuContenido() {
        var d = NuevaDenuncia.recibir(TipoServicio.ENERGIA, CENTRO, "  Conexión ilegal en el poste  ", AHORA);
        assertThat(d.descripcion()).isEqualTo("Conexión ilegal en el poste");
        assertThat(NuevaDenuncia.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("idDenuncia", "tipoServicio", "ubicacion", "descripcion", "fechaRecepcion");
        assertThatThrownBy(() -> NuevaDenuncia.recibir(null, new Ubicacion(40, -3), "corta", AHORA))
                .isInstanceOfSatisfying(ReporteInvalido.class, e -> assertThat(e.errores()).hasSize(3));
    }
}
