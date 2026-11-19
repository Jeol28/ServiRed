package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCasos;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.CasoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;
import java.util.List;
import java.util.Set;

/**
 * abrirCaso: Atención abre un caso al consumir reporte.creado. Es doblemente
 * idempotente: por X-Correlation-Id y porque hay a lo sumo un caso por reporte
 * (una redelivery del evento con otro idCaso devuelve el caso ya abierto).
 *
 * <p>Los eventos (el caso.actualizado de la apertura) van al outbox en la misma
 * transacción y solo si el caso se insertó: así la vista de Indicadores conoce el
 * estado, la prioridad y la empresa desde que el caso existe, y una redelivery no
 * publica una segunda apertura.
 */
public class AbrirCaso {

    private static final OperacionNegocio OPERACION = OperacionNegocio.ABRIR_CASO;

    private final UnidadDeTrabajo transaccion;
    private final RegistroIdempotencia idempotencia;
    private final RepositorioCasos casos;
    private final CatalogoTerritorial catalogo;
    private final Outbox outbox;

    public AbrirCaso(UnidadDeTrabajo transaccion, RegistroIdempotencia idempotencia,
                     RepositorioCasos casos, CatalogoTerritorial catalogo, Outbox outbox) {
        this.transaccion = transaccion;
        this.idempotencia = idempotencia;
        this.casos = casos;
        this.catalogo = catalogo;
        this.outbox = outbox;
    }

    public Resultado ejecutar(ServicioCliente servicio, IdCorrelacion correlacion, CasoNuevo caso,
                              List<EventoSaliente> eventos) {
        OPERACION.autorizar(servicio);
        eventos.forEach(e -> e.validarPara(servicio, correlacion, Set.of("caso.actualizado")));

        return transaccion.ejecutar(() -> {
            boolean nuevo = idempotencia.reclamar(correlacion, OPERACION, servicio,
                    "reporte:" + caso.idReporte()).isEmpty();
            if (nuevo) {
                if (!catalogo.existeMunicipio(caso.codigoMunicipio())) {
                    throw new SolicitudRechazada("Municipio inexistente: " + caso.codigoMunicipio());
                }
                if (casos.insertarSiNoExiste(caso)) {
                    eventos.forEach(outbox::agregar);
                }
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
