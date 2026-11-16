package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import static co.edu.javeriana.servired.persistencia.infraestructura.jdbc.RepositorioReportesJdbc.instante;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCuentas;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.CambiosCuenta;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Credenciales;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Cuenta;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.CuentaNueva;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.Pagina;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Esquema identidad. Las cuentas eliminadas (anonimizadas) no se devuelven. */
@Component
public class RepositorioCuentasJdbc implements RepositorioCuentas {

    private static final String COLUMNAS = "id, correo, rol, id_empresa, nombre, activa, creada_en";

    private final JdbcClient jdbc;

    public RepositorioCuentasJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existeCorreo(String correo) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM identidad.cuentas WHERE correo = :correo)")
                .param("correo", correo).query(Boolean.class).single();
    }

    @Override
    public void insertar(CuentaNueva c) {
        jdbc.sql("""
                        INSERT INTO identidad.cuentas
                            (id, correo, hash_clave, rol, id_empresa, nombre, consentimiento_datos_en, creada_en)
                        VALUES (:id, :correo, :hash, :rol, :empresa, :nombre, :consentimiento, :creada)
                        """)
                .param("id", c.idCuenta())
                .param("correo", c.correo())
                .param("hash", c.hashClave())
                .param("rol", c.rol())
                .param("empresa", c.idEmpresa())
                .param("nombre", c.nombre())
                .param("consentimiento", c.consentimientoDatosEn() == null ? null
                        : Timestamp.from(c.consentimientoDatosEn()))
                .param("creada", Timestamp.from(c.creadaEn()))
                .update();
    }

    @Override
    public Optional<Credenciales> credencialesPorCorreo(String correo) {
        return jdbc.sql("""
                        SELECT id, hash_clave, rol, id_empresa, nombre, activa FROM identidad.cuentas
                        WHERE correo = :correo AND eliminada_en IS NULL
                        """)
                .param("correo", correo)
                .query((rs, n) -> new Credenciales(rs.getObject("id", UUID.class), rs.getString("hash_clave"),
                        rs.getString("rol"), rs.getObject("id_empresa", UUID.class), rs.getString("nombre"),
                        rs.getBoolean("activa")))
                .optional();
    }

    @Override
    public Optional<Cuenta> buscar(UUID idCuenta) {
        return jdbc.sql("SELECT " + COLUMNAS + " FROM identidad.cuentas WHERE id = :id AND eliminada_en IS NULL")
                .param("id", idCuenta)
                .query((rs, n) -> cuenta(rs))
                .optional();
    }

    @Override
    public Pagina listar(String rol, int pagina, int tamano) {
        String filtro = " FROM identidad.cuentas WHERE eliminada_en IS NULL AND (CAST(:rol AS text) IS NULL OR rol = :rol)";
        long total = jdbc.sql("SELECT count(*)" + filtro).param("rol", rol).query(Long.class).single();
        var elementos = jdbc.sql("SELECT " + COLUMNAS + filtro + " ORDER BY creada_en, id LIMIT :limite OFFSET :desde")
                .param("rol", rol)
                .param("limite", tamano)
                .param("desde", (long) pagina * tamano)
                .query((rs, n) -> cuenta(rs))
                .list();
        return new Pagina(elementos, total, pagina, tamano);
    }

    @Override
    public boolean actualizar(UUID idCuenta, CambiosCuenta c) {
        return jdbc.sql("""
                        UPDATE identidad.cuentas SET
                            nombre = COALESCE(:nombre, nombre),
                            activa = COALESCE(:activa, activa),
                            id_empresa = COALESCE(:empresa, id_empresa),
                            hash_clave = COALESCE(:hash, hash_clave),
                            actualizada_en = now()
                        WHERE id = :id AND eliminada_en IS NULL
                        """)
                .param("nombre", c.nombre())
                .param("activa", c.activa())
                .param("empresa", c.idEmpresa())
                .param("hash", c.hashClave())
                .param("id", idCuenta)
                .update() == 1;
    }

    @Override
    public boolean anonimizar(UUID idCuenta) {
        return jdbc.sql("""
                        UPDATE identidad.cuentas SET
                            correo = 'eliminada+' || id || '@servired.invalid',
                            nombre = NULL,
                            hash_clave = '!',
                            activa = false,
                            eliminada_en = now(),
                            actualizada_en = now()
                        WHERE id = :id AND eliminada_en IS NULL
                        """)
                .param("id", idCuenta)
                .update() == 1;
    }

    private static Cuenta cuenta(ResultSet rs) throws SQLException {
        return new Cuenta(rs.getObject("id", UUID.class), rs.getString("correo"), rs.getString("rol"),
                rs.getObject("id_empresa", UUID.class), rs.getString("nombre"), rs.getBoolean("activa"),
                instante(rs, "creada_en"));
    }
}
