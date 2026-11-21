package co.edu.javeriana.servired.identidad.infraestructura.persistencia;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones.AccesoRevocado;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.EmisorTokens.Plan;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones.ResultadoRotacion;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Adaptador hacia las operaciones de identidad de Persistencia
 * (contratos/internos/persistencia.v1.yaml). Reintenta ante fallas de red o 5xx.
 * Cada intento pasa por el circuit breaker (ADR-20): con el circuito abierto se
 * falla al instante con {@link PersistenciaNoDisponible} (503), sin reintentos.
 * El correo nunca va en la URL ni en los logs.
 */
public class ClientePersistencia implements RepositorioCuentas, RepositorioSesiones {

    private static final Logger log = LoggerFactory.getLogger(ClientePersistencia.class);

    private final RestClient http;
    private final PropiedadesPersistencia propiedades;
    private final MeterRegistry metricas;
    private final CircuitBreaker circuito;

    public ClientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades) {
        this(constructor, propiedades, new SimpleMeterRegistry());
    }

    public ClientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades,
                               MeterRegistry metricas) {
        this(constructor, propiedades, metricas, CircuitoPersistencia.crear(PropiedadesCircuito.porDefecto()));
    }

    public ClientePersistencia(RestClient.Builder constructor, PropiedadesPersistencia propiedades,
                               MeterRegistry metricas, CircuitBreaker circuito) {
        this.propiedades = propiedades;
        this.metricas = metricas;
        this.circuito = circuito;
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
                        d.nombre(), d.activa(), d.creadaEn()));
    }

    @Override
    public Optional<Cuenta> consultar(UUID idCuenta, IdCorrelacion correlacion) {
        return opcional(() -> conReintentos("consultarCuenta", () -> http.get()
                .uri("/interno/v1/cuentas/{id}", idCuenta)
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .retrieve().body(CuentaDto.class))).map(CuentaDto::aDominio);
    }

    @Override
    public PaginaCuentas listar(Rol rol, String correo, int pagina, int tamano, IdCorrelacion correlacion) {
        var p = conReintentos("listarCuentas", () -> http.get()
                .uri(b -> b.path("/interno/v1/cuentas").queryParamIfPresent("rol", Optional.ofNullable(rol))
                        .queryParamIfPresent("correo", Optional.ofNullable(correo))
                        .queryParam("pagina", pagina).queryParam("tamano", tamano).build())
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .retrieve().body(PaginaDto.class));
        return new PaginaCuentas(p.elementos().stream().map(CuentaDto::aDominio).toList(), p.total(), p.pagina(),
                p.tamano());
    }

    @Override
    public Optional<Cuenta> actualizar(UUID idCuenta, Cambios c, IdCorrelacion correlacion) {
        var cuerpo = new HashMap<String, Object>();
        if (c.nombre() != null) cuerpo.put("nombre", c.nombre());
        if (c.activa() != null) cuerpo.put("activa", c.activa());
        if (c.idEmpresa() != null) cuerpo.put("idEmpresa", c.idEmpresa());
        if (c.hashClave() != null) cuerpo.put("hashClave", c.hashClave());
        return opcional(() -> conReintentos("actualizarCuenta", () -> http.post()
                .uri("/interno/v1/cuentas/{id}/actualizar", idCuenta)
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .body(cuerpo).retrieve().body(CuentaDto.class))).map(CuentaDto::aDominio);
    }

    @Override
    public boolean eliminar(UUID idCuenta, IdCorrelacion correlacion) {
        return opcional(() -> conReintentos("eliminarCuenta", () -> http.post()
                .uri("/interno/v1/cuentas/{id}/eliminar", idCuenta)
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .retrieve().toBodilessEntity())).isPresent();
    }

    // ----------------------------------------------------------------- sesiones
    @Override
    public void abrir(UUID idSesion, UUID idCuenta, String hashRefresco, Instant expiraEn, Plan acceso,
                      IdCorrelacion correlacion) {
        conReintentos("abrirSesion", () -> http.post().uri("/interno/v1/sesiones/abrir")
                .header(IdCorrelacion.CABECERA, correlacion.valor())
                .body(Map.of("idSesion", idSesion, "idCuenta", idCuenta, "hashRefresco", hashRefresco,
                        "expiraEn", expiraEn, "jtiAcceso", acceso.jti(), "accesoExpiraEn", acceso.expiraEn()))
                .retrieve().toBodilessEntity());
    }

    /** Sin reintentos: repetir una rotación ya aplicada se interpretaría como reutilización del token. */
    @Override
    public ResultadoRotacion rotar(String hashActual, UUID idSesionNueva, String hashNuevo, Instant expiraEn,
                                   Plan acceso, IdCorrelacion correlacion) {
        try {
            var r = circuito.executeSupplier(() -> http.post().uri("/interno/v1/sesiones/rotar")
                    .header(IdCorrelacion.CABECERA, correlacion.valor())
                    .body(Map.of("hashRefrescoActual", hashActual, "idSesionNueva", idSesionNueva,
                            "hashRefrescoNuevo", hashNuevo, "expiraEn", expiraEn, "jtiAcceso", acceso.jti(),
                            "accesoExpiraEn", acceso.expiraEn()))
                    .retrieve().body(RotacionDto.class));
            return ResultadoRotacion.valida(r.cuenta().aDominio());
        } catch (HttpClientErrorException.Conflict e) {
            boolean reutilizado = e.getResponseBodyAsString().contains("REUTILIZADA");
            if (reutilizado) {
                log.warn("se presentó un token de refresco ya usado; se revocaron las sesiones de la cuenta");
            }
            return ResultadoRotacion.invalida(reutilizado);
        } catch (ResourceAccessException | HttpServerErrorException | CallNotPermittedException e) {
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

    @Override
    public List<AccesoRevocado> accesosRevocados(Instant desde, IdCorrelacion correlacion) {
        try {
            return circuito.executeSupplier(() -> List.of(http.get()
                    .uri(u -> {
                        u.path("/interno/v1/sesiones/accesos-revocados");
                        if (desde != null) {
                            u.queryParam("desde", desde.toString());
                        }
                        return u.build();
                    })
                    .header(IdCorrelacion.CABECERA, correlacion.valor())
                    .retrieve().body(AccesoRevocado[].class)));
        } catch (ResourceAccessException | HttpServerErrorException | CallNotPermittedException e) {
            throw new PersistenciaNoDisponible("Persistencia no disponible para listarAccesosRevocados", e);
        }
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
                return circuito.executeSupplier(llamada);
            } catch (CallNotPermittedException e) {
                throw new PersistenciaNoDisponible(
                        "Persistencia no disponible para " + operacion + " (circuito abierto)", e);
            } catch (ResourceAccessException | HttpServerErrorException e) {
                ultima = e;
                log.warn("persistencia falló en {} (intento {}/{}): {}", operacion, intento,
                        propiedades.intentos(), e.getMessage());
                if (intento < propiedades.intentos()) {
                    esperar(intento);
                }
            } catch (HttpClientErrorException e) {
                // ADR-23: antes que cualquier 409 de la operación (que lo leería como «correo ya
                // registrado» o «empresa inexistente»), la correlación de otra solicitud.
                if (e.getStatusCode() == HttpStatus.CONFLICT && e.getResponseBodyAsString().contains("CORRELACION_AJENA")) {
                    throw new co.edu.javeriana.servired.identidad.dominio.Excepciones.CorrelacionAjena();
                }
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
                           boolean activa, Instant creadaEn) {
    }

    record PaginaDto(List<CuentaDto> elementos, long total, int pagina, int tamano) {
    }

    record RotacionDto(CuentaDto cuenta) {
    }
}
