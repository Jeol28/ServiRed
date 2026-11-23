package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Pagina;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroNuevo;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Verificacion;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.util.Set;

/**
 * Registro de auditoría (RF-12): solo inserción, encadenado e idempotente por
 * idEvento. publicarAccionAuditada deja en el outbox el evento de una acción que
 * no tiene otra escritura asociada (p. ej. un inicio de sesión fallido).
 */
public class OperacionesAuditoria {

    private final UnidadDeTrabajo transaccion;
    private final RepositorioAuditoria repositorio;
    private final Outbox outbox;

    public OperacionesAuditoria(UnidadDeTrabajo transaccion, RepositorioAuditoria repositorio, Outbox outbox) {
        this.transaccion = transaccion;
        this.repositorio = repositorio;
        this.outbox = outbox;
    }

    public boolean registrar(ServicioCliente s, RegistroNuevo r) {
        OperacionNegocio.REGISTRAR_AUDITORIA.autorizar(s);
        return transaccion.ejecutar(() -> repositorio.registrar(r));
    }

    public Pagina consultar(ServicioCliente s, Filtro f) {
        OperacionNegocio.CONSULTAR_AUDITORIA.autorizar(s);
        return repositorio.consultar(f);
    }

    public java.util.List<co.edu.javeriana.servired.persistencia.dominio.Auditoria.Segmento> segmentos(ServicioCliente s) {
        OperacionNegocio.CONSULTAR_AUDITORIA.autorizar(s);
        return repositorio.segmentos();
    }

    public Verificacion verificar(ServicioCliente s) {
        OperacionNegocio.VERIFICAR_AUDITORIA.autorizar(s);
        return repositorio.verificar();
    }

    public void publicarAccion(ServicioCliente s, IdCorrelacion correlacion, EventoSaliente evento) {
        OperacionNegocio.PUBLICAR_ACCION_AUDITADA.autorizar(s);
        evento.validarPara(s, correlacion, Set.of("accion.auditada"));
        transaccion.ejecutar(() -> {
            outbox.agregar(evento);
            return null;
        });
    }
}
