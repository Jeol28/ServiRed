package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import java.util.List;
import java.time.Instant;
import co.edu.javeriana.servired.persistencia.dominio.Cuentas.AccesoRevocado;
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
                        INSERT INTO identidad.sesiones (id, id_cuenta, hash_refresco, expira_en, jti_acceso,
                                                        acceso_expira_en)
                        VALUES (:id, :cuenta, :hash, :expira, :jti, :accesoExpira)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", s.idSesion())
                .param("cuenta", s.idCuenta())
                .param("hash", s.hashRefresco())
                .param("expira", Timestamp.from(s.expiraEn()))
                .param("jti", s.jtiAcceso())
                .param("accesoExpira", s.accesoExpiraEn() == null ? null : Timestamp.from(s.accesoExpiraEn()))
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
    public void cerrar(UUID idSesion) {
        jdbc.sql("""
                        WITH RECURSIVE cadena AS (
                            SELECT id FROM identidad.sesiones WHERE id = :id
                            UNION
                            SELECT s.id FROM identidad.sesiones s JOIN cadena c ON s.reemplazada_por = c.id
                        )
                        UPDATE identidad.sesiones s
                        SET revocada_en = coalesce(s.revocada_en, now()),
                            acceso_revocado_en = CASE WHEN s.acceso_expira_en > now()
                                                      THEN coalesce(s.acceso_revocado_en, now())
                                                      ELSE s.acceso_revocado_en END
                        FROM cadena WHERE s.id = cadena.id
                        """)
                .param("id", idSesion)
                .update();
    }

    @Override
    public void revocarTodas(UUID idCuenta) {
        jdbc.sql("""
                        UPDATE identidad.sesiones
                        SET revocada_en = coalesce(revocada_en, now()),
                            acceso_revocado_en = CASE WHEN acceso_expira_en > now()
                                                      THEN coalesce(acceso_revocado_en, now())
                                                      ELSE acceso_revocado_en END
                        WHERE id_cuenta = :cuenta
                          AND (revocada_en IS NULL OR (acceso_expira_en > now() AND acceso_revocado_en IS NULL))
                        """)
                .param("cuenta", idCuenta)
                .update();
    }

    @Override
    public List<AccesoRevocado> accesosRevocados(Instant desde) {
        return jdbc.sql("""
                        SELECT jti_acceso, acceso_expira_en, acceso_revocado_en FROM identidad.sesiones
                        WHERE acceso_revocado_en IS NOT NULL AND acceso_expira_en > now() AND jti_acceso IS NOT NULL
                          AND (CAST(:desde AS timestamptz) IS NULL OR acceso_revocado_en >= :desde)
                        ORDER BY acceso_revocado_en
                        """)
                .param("desde", desde == null ? null : Timestamp.from(desde))
                .query((rs, n) -> new AccesoRevocado(rs.getObject("jti_acceso", UUID.class),
                        instante(rs, "acceso_expira_en"), instante(rs, "acceso_revocado_en")))
                .list();
    }
}
