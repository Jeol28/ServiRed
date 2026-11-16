package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.aplicacion.Vistas.Archivo;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.SaneadorImagenes;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.PoliticaEvidencia;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.ReporteNoEncontrado;
import java.util.UUID;

/** Fotografías de un reporte (RF-1): solo su autor las adjunta y las ve. */
public class GestionEvidencias {

    private final RepositorioReportes repositorio;
    private final SaneadorImagenes saneador;

    public GestionEvidencias(RepositorioReportes repositorio, SaneadorImagenes saneador) {
        this.repositorio = repositorio;
        this.saneador = saneador;
    }

    public Reporte adjuntar(UUID ciudadano, UUID idReporte, byte[] contenido, IdCorrelacion correlacion) {
        Reporte reporte = propio(ciudadano, idReporte, correlacion);
        PoliticaEvidencia.validarCupo(reporte);
        PoliticaEvidencia.validarTamano(contenido == null ? 0 : contenido.length);
        var imagen = saneador.sanear(contenido);
        return repositorio.guardarEvidencia(idReporte, UUID.randomUUID(), imagen, correlacion);
    }

    public Archivo obtener(UUID ciudadano, UUID idReporte, UUID idEvidencia, IdCorrelacion correlacion) {
        propio(ciudadano, idReporte, correlacion);
        return repositorio.obtenerEvidencia(idReporte, idEvidencia, correlacion)
                .orElseThrow(() -> new ReporteNoEncontrado(idReporte));
    }

    private Reporte propio(UUID ciudadano, UUID idReporte, IdCorrelacion correlacion) {
        return repositorio.buscar(idReporte, correlacion)
                .filter(r -> r.perteneceA(ciudadano))
                .orElseThrow(() -> new ReporteNoEncontrado(idReporte));
    }
}
