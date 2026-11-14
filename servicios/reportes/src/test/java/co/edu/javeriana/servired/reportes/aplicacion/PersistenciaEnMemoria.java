package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Municipio;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Doble de prueba de Persistencia, idempotente por correlationId como la real. */
public class PersistenciaEnMemoria implements RepositorioReportes, CatalogoTerritorial {

    public final Map<UUID, Reporte> reportes = new HashMap<>();
    private final Map<String, UUID> porCorrelacion = new HashMap<>();
    public SobreEvento ultimoEvento;
    public boolean disponible = true;

    @Override
    public Municipio resolverMunicipio(Ubicacion ubicacion, IdCorrelacion correlacion) {
        return new Municipio("11001", "BOGOTÁ, D.C.");
    }

    @Override
    public Reporte guardar(NuevoReporte r, String codigoMunicipio, SobreEvento evento, IdCorrelacion correlacion) {
        if (!disponible) {
            throw new IllegalStateException("persistencia caída");
        }
        UUID previo = porCorrelacion.get(correlacion.valor());
        if (previo != null) {
            return reportes.get(previo);
        }
        var guardado = new Reporte(r.idReporte(), r.idCiudadano(), r.tipoServicio(), r.ubicacion(),
                new Municipio(codigoMunicipio, "BOGOTÁ, D.C."), r.inicioEstimado(), r.magnitud(), r.descripcion(),
                EstadoReporte.RECIBIDO, r.fechaRegistro(),
                List.of(new Reporte.EntradaHistoria(EstadoReporte.RECIBIDO, r.fechaRegistro(), "Reporte recibido")));
        reportes.put(r.idReporte(), guardado);
        porCorrelacion.put(correlacion.valor(), r.idReporte());
        ultimoEvento = evento;
        return guardado;
    }

    @Override
    public Optional<Reporte> buscar(UUID idReporte, IdCorrelacion correlacion) {
        return Optional.ofNullable(reportes.get(idReporte));
    }
}
