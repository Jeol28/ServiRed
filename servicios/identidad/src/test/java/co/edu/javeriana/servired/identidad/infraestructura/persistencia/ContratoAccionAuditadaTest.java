package co.edu.javeriana.servired.identidad.infraestructura.persistencia;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.identidad.aplicacion.AccionAuditada;
import co.edu.javeriana.servired.identidad.aplicacion.Solicitante;
import co.edu.javeriana.servired.identidad.contrato.ContratoEventos;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Identidad como productor de accion.auditada v1: cada acción que audita (las mismas de
 * Autenticacion y AdministracionCuentas), con el sobre que arma ClientePersistencia y
 * serializada como viaja, cumple contratos/eventos. La consume Auditoría (.NET).
 */
class ContratoAccionAuditadaTest {

    private static final ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final UUID CUENTA = UUID.randomUUID();
    private static final Solicitante ADMIN = new Solicitante(UUID.randomUUID(), Rol.ADMINISTRADOR);

    static Stream<AccionAuditada> acciones() {
        return Stream.of(
                AccionAuditada.acceso("INICIO_SESION_FALLIDO", false, null, null, Map.of("motivo", "CUENTA_INEXISTENTE")),
                AccionAuditada.acceso("INICIO_SESION_FALLIDO", false, CUENTA, null, Map.of("motivo", "CLAVE_INCORRECTA")),
                AccionAuditada.acceso("INICIO_SESION", true, CUENTA, Rol.CIUDADANO, Map.of()),
                AccionAuditada.seguridad("REFRESCO_REUTILIZADO", Map.of("consecuencia", "sesiones de la cuenta revocadas")),
                AccionAuditada.administracion("CUENTA_CREADA", ADMIN, CUENTA,
                        Map.of("rol", "OPERADOR", "idEmpresa", UUID.randomUUID().toString())),
                AccionAuditada.administracion("CUENTA_ACTUALIZADA", ADMIN, CUENTA, Map.of("cambios", List.of("rol", "activa"))),
                AccionAuditada.administracion("CUENTA_ELIMINADA", ADMIN, CUENTA, Map.of()),
                AccionAuditada.administracion("CLAVE_CAMBIADA", new Solicitante(CUENTA, Rol.CIUDADANO), CUENTA,
                        Map.of("consecuencia", "sesiones revocadas")),
                AccionAuditada.administracion("CUENTA_SUPRIMIDA", new Solicitante(CUENTA, Rol.CIUDADANO), CUENTA, Map.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acciones")
    void cadaAccionAuditadaCumpleElContrato(AccionAuditada accion) throws Exception {
        var sobre = ClientePersistencia.sobre(accion, IdCorrelacion.nuevo());

        var comoViaja = JSON.readTree(JSON.writeValueAsBytes(sobre));

        assertThat(ContratoEventos.errores(comoViaja, "accion.auditada.v1")).isEmpty();
    }
}
