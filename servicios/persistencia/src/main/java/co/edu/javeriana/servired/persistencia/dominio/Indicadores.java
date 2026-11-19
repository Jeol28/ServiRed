package co.edu.javeriana.servired.persistencia.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Modelos de la vista de lectura de Indicadores. Los cálculos y la anonimización los decide Indicadores. */
public final class Indicadores {

    private Indicadores() {
    }

    /**
     * Datos que aporta un evento a la fila del reporte; null = el evento no los trae.
     * {@code fechaEvento} decide qué validación o estado de caso es el más reciente.
     */
    public record ActualizacionVista(UUID idReporte, Instant fechaEvento, String tipoServicio, String codigoMunicipio,
                                     Double latitud, Double longitud, String magnitud, Instant inicioEstimado,
                                     Instant fechaRegistro, Instant fechaRestablecimiento, Integer duracionMinutos,
                                     Boolean validado, Integer confirmacionesNetas, String estadoCaso, String prioridad,
                                     UUID idEmpresa, Instant resueltoEn, Instant cerradoEn) {
    }

    public enum Agrupacion { NINGUNA, SERVICIO, DEPARTAMENTO, MUNICIPIO, MUNICIPIO_SERVICIO_MES, CELDA }

    /** @param situacion ACTIVO (sin restablecer ni resolver), RESUELTO o null */
    public record Filtro(Instant desde, Instant hasta, String tipoServicio, String codigoDepartamento,
                         String codigoMunicipio, UUID idEmpresa, String situacion) {
    }

    /** @param enCabecera solo en celdas: si es una celda de cabecera municipal (true) o rural (false), ADR-28 */
    public record Agregado(String codigo, String nombre, String departamento, String tipoServicio, Instant mes,
                           Double celdaLatitud, Double celdaLongitud, Boolean enCabecera, long reportes, long conDuracion,
                           Double duracionPromedio, Double duracionMediana, Double duracionP90, long vivienda,
                           long cuadra, long barrio, long sector, long validados, long resueltos,
                           Double tiempoResolucionPromedio) {
    }

    /** Fila de la vista para el mapa operativo y la exportación regulatoria (sin ciudadano). */
    public record Hecho(UUID idReporte, String tipoServicio, String codigoMunicipio, String nombreMunicipio,
                        String departamento, double latitud, double longitud, String magnitud, Instant inicioEstimado,
                        Instant fechaRegistro, Instant fechaRestablecimiento, Integer duracionMinutos, boolean validado,
                        int confirmacionesNetas, String estadoCaso, String prioridad, UUID idEmpresa,
                        Instant resueltoEn, Instant cerradoEn) {
    }

    /**
     * Comparación de la vista con los datos de origen (R14). {@code distintasPorColumna}
     * cuenta, por columna, las filas cuyo valor difiere; {@code aplicada} indica si la vista
     * se reescribió (si no, es solo una verificación).
     */
    public record Reconstruccion(long reportesEnOrigen, long filasEnVista, long faltantes, long sobrantes,
                                 long distintas, java.util.Map<String, Long> distintasPorColumna, boolean aplicada) {

        public boolean alineada() {
            return faltantes == 0 && sobrantes == 0 && distintas == 0;
        }
    }

    public record EstadoVista(long reportes, long municipios, Instant primerReporte, Instant ultimoReporte,
                              Instant actualizadoEn) {
    }

    public record PaginaHechos(List<Hecho> elementos, boolean hayMas) {
    }
}
