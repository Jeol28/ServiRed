package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import static co.edu.javeriana.servired.persistencia.infraestructura.jdbc.RepositorioReportesJdbc.instante;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioSesiones;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.SesionNueva;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RepositorioSesionesJdbc implements RepositorioSesiones {

    private final JdbcClient jdbc;

    public RepositorioSesionesJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertar(SesionNueva s) {
        jdbc.sql("""
                        INSERT INTO identidad.sesiones (id, id_cuenta, hash_refresco, expira_en)
                        VALUES (:id, :cuenta, :hash, :expira)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", s.idSesion())
                .param("cuenta", s.idCuenta())
                .param("hash", s.hashRefresco())
                .param("expira", Timestamp.from(s.expiraEn()))
                .update();
    }

    @Override
    public Optional<SesionVigente> bloquearPorHash(String hashRefresco) {
        return jdbc.sql("""
                        SELECT id, id_cuenta, expira_en, revocada_en IS NOT NULL AS revocada
                        FROM identidad.sesiones WHERE hash_refresco = :hash FOR UPDATE
                        """)
                .param("hash", hashRefresco)
                .query((rs, n) -> new SesionVigente(rs.getObject("id", UUID.class),
                        rs.getObject("id_cuenta", UUID.class), instante(rs, "expira_en"), rs.getBoolean("revocada")))
                .optional();
    }

    @Override
    public void revocar(UUID idSesion, UUID reemplazadaPor) {
        jdbc.sql("UPDATE identidad.sesiones SET revocada_en = now(), reemplazada_por = :nueva WHERE id = :id")
                .param("nueva", reemplazadaPor)
                .param("id", idSesion)
                .update();
    }

    @Override
    public void revocarTodas(UUID idCuenta) {
        jdbc.sql("UPDATE identidad.sesiones SET revocada_en = now() WHERE id_cuenta = :cuenta AND revocada_en IS NULL")
                .param("cuenta", idCuenta)
                .update();
    }
}
