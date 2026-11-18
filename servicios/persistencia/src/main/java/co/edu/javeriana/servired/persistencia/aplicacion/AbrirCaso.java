package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCasos;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.CasoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;

/**
 * abrirCaso: Atención abre un caso al consumir reporte.creado. Es doblemente
 * idempotente: por X-Correlation-Id y porque hay a lo sumo un caso por reporte
 * (una redelivery del evento con otro idCaso devuelve el caso ya abierto).
 */
public class AbrirCaso {

    private static final OperacionNegocio OPERACION = OperacionNegocio.ABRIR_CASO;

    private final UnidadDeTrabajo transaccion;
    private final RegistroIdempotencia idempotencia;
    private final RepositorioCasos casos;
    private final CatalogoTerritorial catalogo;

    public AbrirCaso(UnidadDeTrabajo transaccion, RegistroIdempotencia idempotencia,
                     RepositorioCasos casos, CatalogoTerritorial catalogo) {
        this.transaccion = transaccion;
        this.idempotencia = idempotencia;
        this.casos = casos;
        this.catalogo = catalogo;
    }

    public Resultado ejecutar(ServicioCliente servicio, IdCorrelacion correlacion, CasoNuevo caso) {
        OPERACION.autorizar(servicio);

        return transaccion.ejecutar(() -> {
            boolean nuevo = idempotencia.reclamar(correlacion, OPERACION, servicio,
                    "reporte:" + caso.idReporte()).isEmpty();
            if (nuevo) {
                if (!catalogo.existeMunicipio(caso.codigoMunicipio())) {
                    throw new SolicitudRechazada("Municipio inexistente: " + caso.codigoMunicipio());
                }
                casos.insertarSiNoExiste(caso);
            }
            CasoGuardado guardado = casos.buscarPorReporte(caso.idReporte())
                    .orElseThrow(() -> new IllegalStateException("Caso no encontrado tras abrirlo"));
            if (nuevo) {
                idempotencia.registrarResultado(correlacion, OPERACION, guardado.idCaso());
            }
            return new Resultado(guardado, nuevo && guardado.idCaso().equals(caso.idCaso()));
        });
    }

    public record Resultado(CasoGuardado caso, boolean nuevo) {
    }
}
