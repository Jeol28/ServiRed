package co.edu.javeriana.servired.identidad.infraestructura.persistencia;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.identidad.dominio.Correo;
import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.DatosInvalidos;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CorreoYaRegistrado;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.NuevaCuenta;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Adaptador hacia las operaciones de identidad de Persistencia
 * (contratos/internos/persistencia.v1.yaml). Reintenta ante fallas de red o 5xx.
 * El correo nunca va en la URL ni en los logs.
 */
public class ClientePersistencia implements RepositorioCuentas, RepositorioSesiones {

    private static final Logger log = LoggerFactory.getLogger(ClientePersistencia.class);

    private final RestClient http;
    private final PropiedadesPersistencia propiedades;

    public ClientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades) {
        this.propiedades = propiedades;
        this.http = constructor.clone()
                .baseUrl(propiedades.url())
                .defaultHeader("X-Clave-Servicio", propiedades.clave())
                .build();
    }

    // ------------------------------------------------------------------ cuentas
    @Override
    public Cuenta registrar(NuevaCuenta c, String hashClave, IdCorrelacion correlacion) {
        var cuerpo = new SolicitudRegistrar(c.idCuenta(), c.correo().valor(), hashClave, c.rol().name(),
                c.idEmpresa(), c.nombre(), c.consentimientoDatosEn(), c.creadaEn());
        try {
            return conReintentos("registrarCuenta", () -> http.post().uri("/interno/v1/cuentas/registrar")
                    .header(IdCorrelacion.CABECERA, correlacion.valor())
                    .body(cuerpo).retrieve().body(CuentaDto.class)).aDominio();
        } catch (HttpClientErrorException.Conflict e) {
            if (e.getResponseBodyAsString().contains("correo ya está registrado")) {
                throw new CorreoYaRegistrado();
            }
            throw new DatosInvalidos("idEmpresa", "la empresa no existe");
        }
    }

    @Override
    public Optional<Credenciales> credenciales(Correo correo, IdCorrelacion correlacion) {
        return opcional(() -> conReintentos("buscarCredenciales", () -> http.post()
                .uri("/interno/v1/cuentas/credenciales")
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .body(Map.of("correo", correo.valor())).retrieve().body(CredencialesDto.class)))
                .map(d -> new Credenciales(d.idCuenta(), d.hashClave(), Rol.valueOf(d.rol()), d.idEmpresa(),
                        d.nombre(), d.activa()));
    }

    @Override
    public Optional<Cuenta> consultar(UUID idCuenta, IdCorrelacion correlacion) {
        return opcional(() -> conReintentos("consultarCuenta", () -> http.get()
                .uri("/interno/v1/cuentas/{id}", idCuenta)
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .retrieve().body(CuentaDto.class))).map(CuentaDto::aDominio);
    }

    @Override
    public PaginaCuentas listar(Rol rol, int pagina, int tamano, IdCorrelacion correlacion) {
        var p = conReintentos("listarCuentas", () -> http.get()
                .uri(b -> b.path("/interno/v1/cuentas").queryParamIfPresent("rol", Optional.ofNullable(rol))
                        .queryParam("pagina", pagina).queryParam("tamano", tamano).build())
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .retrieve().body(PaginaDto.class));
        return new PaginaCuentas(p.elementos().stream().map(CuentaDto::aDominio).toList(), p.total(), p.pagina(),
                p.tamano());
    }

    @Override
    public Optional<Cuenta> actualizar(UUID idCuenta, Cambios c, IdCorrelacion correlacion) {
        return opcional(() -> conReintentos("actualizarCuenta", () -> http.post()
                .uri("/interno/v1/cuentas/{id}/actualizar", idCuenta)
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .body(c).retrieve().body(CuentaDto.class))).map(CuentaDto::aDominio);
    }

    @Override
    public boolean eliminar(UUID idCuenta, IdCorrelacion correlacion) {
        return opcional(() -> conReintentos("eliminarCuenta", () -> http.post()
                .uri("/interno/v1/cuentas/{id}/eliminar", idCuenta)
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .body(Map.of()).retrieve().toBodilessEntity())).isPresent();
    }

    // ----------------------------------------------------------------- sesiones
    @Override
    public void abrir(UUID idSesion, UUID idCuenta, String hashRefresco, Instant expiraEn, IdCorrelacion correlacion) {
        conReintentos("abrirSesion", () -> http.post().uri("/interno/v1/sesiones/abrir")
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .body(Map.of("idSesion", idSesion, "idCuenta", idCuenta, "hashRefresco", hashRefresco,
                        "expiraEn", expiraEn))
                .retrieve().toBodilessEntity());
    }

    /** Sin reintentos: repetir una rotación ya aplicada se interpretaría como reutilización del token. */
    @Override
    public Optional<Cuenta> rotar(String hashActual, UUID idSesionNueva, String hashNuevo, Instant expiraEn,
                                  IdCorrelacion correlacion) {
        try {
            var r = http.post().uri("/interno/v1/sesiones/rotar")
                    .header(IdCorrelacion.CABECERA, correlacion.valor())
                    .body(Map.of("hashRefrescoActual", hashActual, "idSesionNueva", idSesionNueva,
                            "hashRefrescoNuevo", hashNuevo, "expiraEn", expiraEn))
                    .retrieve().body(RotacionDto.class);
            return Optional.of(r.cuenta().aDominio());
        } catch (HttpClientErrorException.Conflict e) {
            if (e.getResponseBodyAsString().contains("REUTILIZADA")) {
                log.warn("se presentó un token de refresco ya usado; se revocaron las sesiones de la cuenta");
            }
            return Optional.empty();
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new PersistenciaNoDisponible("Persistencia no disponible para rotarSesion", e);
        }
    }

    @Override
    public void cerrar(String hashRefresco, IdCorrelacion correlacion) {
        conReintentos("cerrarSesion", () -> http.post().uri("/interno/v1/sesiones/cerrar")
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .body(Map.of("hashRefresco", hashRefresco))
                .retrieve().toBodilessEntity());
    }

    // ---------------------------------------------------------------- auxiliares
    private static <T> Optional<T> opcional(Supplier<T> llamada) {
        try {
            return Optional.ofNullable(llamada.get());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }

    private <T> T conReintentos(String operacion, Supplier<T> llamada) {
        RuntimeException ultima = null;
        for (int intento = 1; intento <= propiedades.intentos(); intento++) {
            try {
                return llamada.get();
            } catch (ResourceAccessException | HttpServerErrorException e) {
                ultima = e;
                log.warn("persistencia falló en {} (intento {}/{}): {}", operacion, intento,
                        propiedades.intentos(), e.getMessage());
                if (intento < propiedades.intentos()) {
                    esperar(intento);
                }
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode() == HttpStatus.NOT_FOUND || e.getStatusCode() == HttpStatus.CONFLICT) {
                    throw e;
                }
                throw new IllegalStateException("Persistencia rechazó " + operacion + ": " + e.getStatusCode(), e);
            }
        }
        throw new PersistenciaNoDisponible("Persistencia no disponible para " + operacion, ultima);
    }

    private void esperar(int intento) {
        try {
            Thread.sleep(propiedades.esperaEntreIntentos().toMillis() * intento);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record SolicitudRegistrar(UUID idCuenta, String correo, String hashClave, String rol, UUID idEmpresa,
                              String nombre, Instant consentimientoDatosEn, Instant creadaEn) {
    }

    record CuentaDto(UUID idCuenta, String correo, String rol, UUID idEmpresa, String nombre, boolean activa,
                     Instant creadaEn) {
        Cuenta aDominio() {
            return new Cuenta(idCuenta, new Correo(correo), Rol.valueOf(rol), idEmpresa, nombre, activa, creadaEn);
        }
    }

    record CredencialesDto(UUID idCuenta, String hashClave, String rol, UUID idEmpresa, String nombre,
                           boolean activa) {
    }

    record PaginaDto(List<CuentaDto> elementos, long total, int pagina, int tamano) {
    }

    record RotacionDto(CuentaDto cuenta) {
    }
}
