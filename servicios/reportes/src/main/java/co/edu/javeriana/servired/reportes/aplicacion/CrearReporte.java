package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Magnitud;
import co.edu.javeriana.servired.reportes.dominio.Municipio;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.time.Clock;
import java.util.UUID;

/**
 * RF-1 / RF-2: valida el reporte, resuelve su municipio y lo persiste junto con
 * reporte.creado. Solo depende de Persistencia: Atención e Indicadores se
 * enteran por el evento, así que su falla no impide registrar (RNF-DC2).
 */
public class CrearReporte {

    private final CatalogoTerritorial catalogo;
    private final RepositorioReportes repositorio;
    private final Clock reloj;

    public CrearReporte(CatalogoTerritorial catalogo, RepositorioReportes repositorio, Clock reloj) {
        this.catalogo = catalogo;
        this.repositorio = repositorio;
        this.reloj = reloj;
    }

    public Reporte ejecutar(UUID idCiudadano, Comando comando, IdCorrelacion correlacion) {
        NuevoReporte reporte = NuevoReporte.registrar(idCiudadano, comando.tipoServicio(), comando.ubicacion(),
                comando.inicioEstimado(), comando.magnitud(), comando.descripcion(), reloj.instant());
        Municipio municipio = catalogo.resolverMunicipio(reporte.ubicacion(), correlacion);
        SobreEvento evento = EventosReporte.reporteCreado(reporte, municipio.codigo(), correlacion);
        return repositorio.guardar(reporte, municipio.codigo(), evento, correlacion);
    }

    public record Comando(TipoServicio tipoServicio, Ubicacion ubicacion, java.time.Instant inicioEstimado,
                          Magnitud magnitud, String descripcion) {
    }
}
