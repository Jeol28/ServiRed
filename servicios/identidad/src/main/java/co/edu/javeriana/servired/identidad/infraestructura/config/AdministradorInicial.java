package co.edu.javeriana.servired.identidad.infraestructura.config;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.CifradorClaves;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.identidad.dominio.Correo;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CorreoYaRegistrado;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.NuevaCuenta;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Crea el primer administrador desde .env (IDENTIDAD_ADMIN_CORREO y
 * IDENTIDAD_ADMIN_CLAVE) si aún no existe. Es idempotente: usa un
 * correlationId derivado del correo. Reintenta en segundo plano mientras
 * Persistencia no esté disponible.
 */
@Component
public class AdministradorInicial {

    private static final Logger log = LoggerFactory.getLogger(AdministradorInicial.class);

    private final RepositorioCuentas cuentas;
    private final CifradorClaves cifrador;
    private final Clock reloj;
    private final String correo;
    private final String clave;

    public AdministradorInicial(RepositorioCuentas cuentas, CifradorClaves cifrador, Clock reloj,
                                @Value("${servired.admin-inicial.correo:}") String correo,
                                @Value("${servired.admin-inicial.clave:}") String clave) {
        this.cuentas = cuentas;
        this.cifrador = cifrador;
        this.reloj = reloj;
        this.correo = correo;
        this.clave = clave;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (correo.isBlank() || clave.isBlank()) {
            log.info("sin administrador inicial configurado");
            return;
        }
        Thread.ofVirtual().name("administrador-inicial").start(this::asegurarConReintentos);
    }

    void asegurarConReintentos() {
        for (int intento = 1; intento <= 30; intento++) {
            try {
                asegurar();
                return;
            } catch (RuntimeException e) {
                log.warn("no se pudo asegurar el administrador inicial (intento {}): {}", intento, e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    void asegurar() {
        var c = Correo.de(correo);
        var correlacion = new IdCorrelacion(
                UUID.nameUUIDFromBytes(("admin-inicial:" + c.valor()).getBytes(StandardCharsets.UTF_8)).toString());
        if (cuentas.credenciales(c, correlacion).isPresent()) {
            log.info("administrador inicial ya existe");
            return;
        }
        try {
            var nueva = NuevaCuenta.personal(correo, clave, "Administrador", Rol.ADMINISTRADOR, null, reloj.instant());
            cuentas.registrar(nueva, cifrador.cifrar(clave), correlacion);
            log.info("administrador inicial creado");
        } catch (CorreoYaRegistrado e) {
            log.info("administrador inicial ya existe");
        }
    }
}
