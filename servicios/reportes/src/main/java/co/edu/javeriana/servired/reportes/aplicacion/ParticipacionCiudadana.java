package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.aplicacion.Vistas.PaginaReportes;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ReporteCercano;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ResultadoValidacion;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.Voto;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes.ConflictoDeVersion;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes.VotoRepetido;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.ObjetoValidacion;
import co.edu.javeriana.servired.reportes.dominio.ReglasValidacion;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.ReporteNoEncontrado;
import co.edu.javeriana.servired.reportes.dominio.SentidoValidacion;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Seguimiento y participación del ciudadano: sus reportes (RF-2), el
 * restablecimiento (RF-3) y la validación comunitaria de eventos cercanos (RF-4).
 */
public class ParticipacionCiudadana {

    static final int INTENTOS_POR_CONCURRENCIA = 3;

    private final RepositorioReportes repositorio;
    private final ReglasValidacion reglas;
    private final Clock reloj;

    public ParticipacionCiudadana(RepositorioReportes repositorio, ReglasValidacion reglas, Clock reloj) {
        this.repositorio = repositorio;
        this.reglas = reglas;
        this.reloj = reloj;
    }

    public PaginaReportes misReportes(UUID ciudadano, int pagina, int tamano, IdCorrelacion correlacion) {
        return repositorio.listarDeCiudadano(ciudadano, pagina, tamano, correlacion);
    }

    /** Eventos activos a menos del radio de validación, sin incluir los propios. */
    public List<ReporteCercano> cercanos(UUID ciudadano, Ubicacion centro, IdCorrelacion correlacion) {
        if (centro == null || !centro.estaEnColombia()) {
            throw new co.edu.javeriana.servired.reportes.dominio.ReporteInvalido(List.of(
                    new co.edu.javeriana.servired.reportes.dominio.ErrorDeCampo("ubicacion", "debe estar dentro de Colombia")));
        }
        return repositorio.cercanos(centro, reglas.radioMetros(), ciudadano, correlacion);
    }

    public Reporte restablecer(UUID ciudadano, UUID idReporte, java.time.Instant fecha, String comentario,
                               IdCorrelacion correlacion) {
        Reporte reporte = repositorio.buscar(idReporte, correlacion).orElseThrow(() -> new ReporteNoEncontrado(idReporte));
        var restablecimiento = reporte.restablecer(ciudadano, fecha, comentario, reloj.instant());
        return repositorio.registrarRestablecimiento(idReporte, restablecimiento,
                EventosReporte.restablecimientoRegistrado(idReporte, restablecimiento, correlacion, reloj.instant()),
                correlacion);
    }

    /**
     * Calcula el resultado con los votos leídos y lo guarda con concurrencia
     * optimista; si otro voto entró en medio, vuelve a leer y recalcular. Votar
     * dos veces no cambia nada y devuelve el estado actual (idempotente).
     */
    public ResultadoValidacion validar(UUID ciudadano, UUID idReporte, ObjetoValidacion objeto,
                                       SentidoValidacion sentido, Ubicacion ubicacion, IdCorrelacion correlacion) {
        for (int intento = 1; ; intento++) {
            Reporte reporte = repositorio.buscar(idReporte, correlacion)
                    .orElseThrow(() -> new ReporteNoEncontrado(idReporte));
            var resultado = reglas.evaluar(reporte, ciudadano, objeto, sentido, ubicacion);
            var voto = new Voto(objeto, sentido, ciudadano, reloj.instant(), reporte.versionValidaciones());
            // Cada intento es una escritura distinta: correlación derivada para no chocar con la idempotencia.
            IdCorrelacion correlacionIntento = intento == 1 ? correlacion : IdCorrelacion.nuevo();
            try {
                Reporte guardado = repositorio.registrarValidacion(idReporte, voto,
                        EventosReporte.validacionRegistrada(idReporte, resultado, correlacionIntento, reloj.instant()),
                        correlacionIntento);
                var conteo = guardado.conteo(objeto);
                return new ResultadoValidacion(idReporte, objeto, conteo, reglas.validado(conteo), true);
            } catch (VotoRepetido e) {
                var conteo = reporte.conteo(objeto);
                return new ResultadoValidacion(idReporte, objeto, conteo, reglas.validado(conteo), false);
            } catch (ConflictoDeVersion e) {
                if (intento >= INTENTOS_POR_CONCURRENCIA) {
                    throw e;
                }
            }
        }
    }
}
