package co.edu.javeriana.servired.persistencia.dominio;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Evento ya construido por el servicio dueño, que Persistencia guarda en el
 * outbox en la misma transacción de la operación (ADR-06). Persistencia no
 * interpreta los datos: solo valida el sobre y que el emisor sea el dueño.
 *
 * @param sobre JSON completo del sobre (contratos/eventos/esquemas/sobre-evento.v1.json)
 */
public record EventoSaliente(UUID idEvento, String tipo, int version, UUID correlationId,
                             String origen, String sobre) {

    /** Dueño de cada tipo de evento: solo él puede emitirlo. */
    private static final Map<String, ServicioCliente> DUENOS = Map.of(
            "reporte.creado", ServicioCliente.REPORTES,
            "restablecimiento.registrado", ServicioCliente.REPORTES,
            "validacion.registrada", ServicioCliente.REPORTES,
            "denuncia.recibida", ServicioCliente.REPORTES,
            "caso.actualizado", ServicioCliente.ATENCION,
            "caso.cerrado", ServicioCliente.ATENCION);

    public EventoSaliente {
        Objects.requireNonNull(idEvento, "idEvento");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(sobre, "sobre");
        if (!DUENOS.containsKey(tipo)) {
            throw new SolicitudRechazada("Tipo de evento desconocido: " + tipo);
        }
        if (version < 1) {
            throw new SolicitudRechazada("La versión del evento debe ser >= 1");
        }
    }

    public String routingKey() {
        return tipo + ".v" + version;
    }

    /**
     * Comprueba que el evento pertenece a la operación y a la transacción en curso,
     * y que lo emite su servicio dueño.
     */
    public void validarPara(ServicioCliente emisor, IdCorrelacion correlacion, Set<String> tiposPermitidos) {
        if (DUENOS.get(tipo) != emisor || !emisor.nombre().equals(origen)) {
            throw new AccesoDenegado("El servicio " + emisor.nombre() + " no es dueño del evento " + tipo);
        }
        if (!tiposPermitidos.contains(tipo)) {
            throw new SolicitudRechazada("La operación no admite el evento " + tipo);
        }
        if (!correlationId.toString().equals(correlacion.valor())) {
            throw new SolicitudRechazada("El correlationId del evento no coincide con X-Correlation-Id");
        }
    }
}
