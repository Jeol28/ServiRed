package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.aplicacion.Vistas.DenunciaRecibida;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ImagenSaneada;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.SaneadorImagenes;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.NuevaDenuncia;
import co.edu.javeriana.servired.reportes.dominio.PoliticaEvidencia;
import co.edu.javeriana.servired.reportes.dominio.TipoServicio;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.time.Clock;

/**
 * Receptor de denuncias anónimas (RF-5, ADR-09). No recibe ni deriva ningún
 * identificador del denunciante; la fotografía se sanea (sin EXIF ni GPS).
 */
public class RecibirDenuncia {

    private final CatalogoTerritorial catalogo;
    private final RepositorioReportes repositorio;
    private final SaneadorImagenes saneador;
    private final Clock reloj;

    public RecibirDenuncia(CatalogoTerritorial catalogo, RepositorioReportes repositorio, SaneadorImagenes saneador,
                           Clock reloj) {
        this.catalogo = catalogo;
        this.repositorio = repositorio;
        this.saneador = saneador;
        this.reloj = reloj;
    }

    public DenunciaRecibida ejecutar(TipoServicio tipo, Ubicacion ubicacion, String descripcion, byte[] foto,
                                     IdCorrelacion correlacion) {
        NuevaDenuncia denuncia = NuevaDenuncia.recibir(tipo, ubicacion, descripcion, reloj.instant());
        ImagenSaneada imagen = null;
        if (foto != null && foto.length > 0) {
            PoliticaEvidencia.validarTamano(foto.length);
            imagen = saneador.sanear(foto);
        }
        String municipio = catalogo.resolverMunicipio(denuncia.ubicacion(), correlacion).codigo();
        return repositorio.guardarDenuncia(denuncia, municipio, imagen,
                EventosReporte.denunciaRecibida(denuncia, municipio, imagen != null, correlacion), correlacion);
    }
}
