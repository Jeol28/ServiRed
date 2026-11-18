package co.edu.javeriana.servired.identidad.aplicacion;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.identidad.aplicacion.puertos.RepositorioSesiones.ResultadoRotacion;
import co.edu.javeriana.servired.identidad.dominio.Correo;
import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.Excepciones.CorreoYaRegistrado;
import co.edu.javeriana.servired.identidad.dominio.IdCorrelacion;
import co.edu.javeriana.servired.identidad.dominio.NuevaCuenta;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Doble de Persistencia con la misma semántica (rotación con detección de reutilización). */
public class PersistenciaEnMemoria implements RepositorioCuentas, RepositorioSesiones,
        co.edu.javeriana.servired.identidad.aplicacion.puertos.RegistroAcciones {

    /** Acciones auditables recibidas (con la operación o sueltas). */
    public final java.util.List<AccionAuditada> acciones = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void publicar(AccionAuditada accion, IdCorrelacion correlacion) {
        acciones.add(accion);
    }

    @Override
    public Cuenta registrar(NuevaCuenta c, String hash, java.util.List<AccionAuditada> a, IdCorrelacion correlacion) {
        var cuenta = registrar(c, hash, correlacion);
        acciones.addAll(a);
        return cuenta;
    }

    @Override
    public Optional<Cuenta> actualizar(UUID id, Cambios c, java.util.List<AccionAuditada> a, IdCorrelacion correlacion) {
        var r = actualizar(id, c, correlacion);
        r.ifPresent(x -> acciones.addAll(a));
        return r;
    }

    @Override
    public boolean eliminar(UUID id, java.util.List<AccionAuditada> a, IdCorrelacion correlacion) {
        boolean r = eliminar(id, correlacion);
        if (r) acciones.addAll(a);
        return r;
    }

    record Fila(Cuenta cuenta, String hash) {
    }

    record Sesion(UUID idCuenta, Instant expira, boolean revocada) {
    }

    public final Map<UUID, Fila> cuentas = new ConcurrentHashMap<>();
    public final Map<String, Sesion> sesiones = new ConcurrentHashMap<>();

    @Override
    public Cuenta registrar(NuevaCuenta c, String hashClave, IdCorrelacion correlacion) {
        if (cuentas.values().stream().anyMatch(f -> f.cuenta().correo().equals(c.correo()))) {
            throw new CorreoYaRegistrado();
        }
        var cuenta = new Cuenta(c.idCuenta(), c.correo(), c.rol(), c.idEmpresa(), c.nombre(), true, c.creadaEn());
        cuentas.put(c.idCuenta(), new Fila(cuenta, hashClave));
        return cuenta;
    }

    @Override
    public Optional<Credenciales> credenciales(Correo correo, IdCorrelacion correlacion) {
        return cuentas.values().stream().filter(f -> f.cuenta().correo().equals(correo)).findFirst()
                .map(f -> new Credenciales(f.cuenta().idCuenta(), f.hash(), f.cuenta().rol(), f.cuenta().idEmpresa(),
                        f.cuenta().nombre(), f.cuenta().activa(), f.cuenta().creadaEn()));
    }

    @Override
    public Optional<Cuenta> consultar(UUID idCuenta, IdCorrelacion correlacion) {
        return Optional.ofNullable(cuentas.get(idCuenta)).map(Fila::cuenta);
    }

    @Override
    public PaginaCuentas listar(Rol rol, String correo, int pagina, int tamano, IdCorrelacion correlacion) {
        var lista = cuentas.values().stream().map(Fila::cuenta).filter(c -> rol == null || c.rol() == rol)
                .filter(c -> correo == null || c.correo().valor().toLowerCase().contains(correo.toLowerCase())).toList();
        return new PaginaCuentas(lista, lista.size(), pagina, tamano);
    }

    @Override
    public Optional<Cuenta> actualizar(UUID idCuenta, Cambios c, IdCorrelacion correlacion) {
        var fila = cuentas.get(idCuenta);
        if (fila == null) {
            return Optional.empty();
        }
        var a = fila.cuenta();
        var nueva = new Cuenta(a.idCuenta(), a.correo(), a.rol(), c.idEmpresa() != null ? c.idEmpresa() : a.idEmpresa(),
                c.nombre() != null ? c.nombre() : a.nombre(), c.activa() != null ? c.activa() : a.activa(), a.creadaEn());
        cuentas.put(idCuenta, new Fila(nueva, c.hashClave() != null ? c.hashClave() : fila.hash()));
        if (Boolean.FALSE.equals(c.activa()) || c.hashClave() != null) {
            revocarTodas(idCuenta);
        }
        return Optional.of(nueva);
    }

    @Override
    public boolean eliminar(UUID idCuenta, IdCorrelacion correlacion) {
        revocarTodas(idCuenta);
        return cuentas.remove(idCuenta) != null;
    }

    @Override
    public void abrir(UUID idSesion, UUID idCuenta, String hash, Instant expiraEn, IdCorrelacion correlacion) {
        sesiones.put(hash, new Sesion(idCuenta, expiraEn, false));
    }

    @Override
    public synchronized ResultadoRotacion rotar(String hashActual, UUID idNueva, String hashNuevo, Instant expiraEn,
                                                IdCorrelacion correlacion) {
        var s = sesiones.get(hashActual);
        if (s == null) {
            return ResultadoRotacion.invalida(false);
        }
        if (s.revocada()) {
            revocarTodas(s.idCuenta());
            return ResultadoRotacion.invalida(true);
        }
        var cuenta = consultar(s.idCuenta(), correlacion).filter(Cuenta::activa);
        if (cuenta.isEmpty() || !s.expira().isAfter(Instant.now())) {
            return ResultadoRotacion.invalida(false);
        }
        sesiones.put(hashActual, new Sesion(s.idCuenta(), s.expira(), true));
        sesiones.put(hashNuevo, new Sesion(s.idCuenta(), expiraEn, false));
        return ResultadoRotacion.valida(cuenta.get());
    }

    @Override
    public void cerrar(String hash, IdCorrelacion correlacion) {
        sesiones.computeIfPresent(hash, (h, s) -> new Sesion(s.idCuenta(), s.expira(), true));
    }

    public long sesionesActivas(UUID idCuenta) {
        return sesiones.values().stream().filter(s -> s.idCuenta().equals(idCuenta) && !s.revocada()).count();
    }

    private void revocarTodas(UUID idCuenta) {
        sesiones.replaceAll((h, s) -> s.idCuenta().equals(idCuenta) ? new Sesion(s.idCuenta(), s.expira(), true) : s);
    }
}
