package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Modelos de las operaciones de atención y moderación (dominio de Atención). */
public final class Atencion {

    private Atencion() {
    }

    public record Empresa(UUID idEmpresa, String nombre) {
    }

    /** Empresa prestadora real del catálogo (ADR-35). */
    public record EmpresaPrestadora(UUID idEmpresa, String nit, String nombre, List<String> servicios) {
    }

    /**
     * Filtro del catálogo de empresas (null = sin filtro): las dueñas de al menos una zona con ese
     * servicio, en ese departamento o municipio (ADR-35).
     */
    public record FiltroEmpresas(String tipoServicio, String codigoDepartamento, String codigoMunicipio) {
    }

    /**
     * Zona de servicio de un punto y su única empresa dueña para un servicio (ADR-35).
     * {@code empresa} es null si nadie presta ese servicio allí.
     */
    public record Cobertura(String idZona, String nombreZona, Empresa empresa) {
    }

    /** Dónde buscar la cobertura: un punto, la ubicación guardada de una denuncia o un municipio. */
    public record ConsultaCobertura(Double latitud, Double longitud, UUID idDenuncia, String codigoMunicipio,
                                    String tipoServicio) {
    }

    /**
     * Filtros de la bandeja de casos; null = sin filtro. El departamento son los dos
     * primeros dígitos del código DIVIPOLA del municipio.
     */
    public record FiltroCasos(String estado, String tipoServicio, String codigoMunicipio, String codigoDepartamento,
                              String prioridad,
                              UUID idEmpresa, boolean soloSinEmpresa, UUID asignadoA, UUID idReporte, int pagina,
                              int tamano) {
    }

    public record PaginaCasos(List<CasoGuardado> elementos, long total, int pagina, int tamano) {
    }

    /**
     * Cambios de un caso decididos por Atención; null = sin cambio. Se aplican solo
     * si la versión coincide (concurrencia optimista).
     */
    public record CambiosCaso(int versionEsperada, String estado, String prioridad, Boolean prioridadManual,
                              UUID asignadoA, boolean quitarAsignacion, UUID idEmpresa, Boolean corteValidado,
                              String resolucion, Instant resueltoEn, Instant cerradoEn, String detalleHistoria,
                              UUID autor, Instant fecha) {
    }

    public record ModeracionNueva(UUID idDenuncia, UUID idEmpresa, Instant recibidaEn) {
    }

    public record Moderacion(UUID idDenuncia, String tipoServicio, String codigoMunicipio, String nombreMunicipio,
                             double latitud, double longitud, String descripcion, Instant recibidaEn, UUID idEmpresa,
                             String estado, String nota, UUID moderadaPor, Instant moderadaEn, int version,
                             List<UUID> evidencias) {
    }

    public record PaginaModeraciones(List<Moderacion> elementos, long total, int pagina, int tamano) {
    }

    public record Decision(int versionEsperada, String estado, String nota, UUID moderadaPor, Instant fecha) {
    }

    /** Cambio del estado visible del reporte por un evento de caso (ADR-07). */
    public record EstadoPorCaso(UUID idEvento, String estadoNuevo, List<String> desde, String detalle, Instant fecha) {
    }
}
