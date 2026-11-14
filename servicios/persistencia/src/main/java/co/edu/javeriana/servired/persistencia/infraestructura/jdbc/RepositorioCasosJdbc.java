package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import static co.edu.javeriana.servired.persistencia.infraestructura.jdbc.RepositorioReportesJdbc.instante;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCasos;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.CasoNuevo;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Esquema atencion. */
@Component
public class RepositorioCasosJdbc implements RepositorioCasos {

    private final JdbcClient jdbc;

    public RepositorioCasosJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertarSiNoExiste(CasoNuevo c) {
        jdbc.sql("""
                        INSERT INTO atencion.casos
                            (id, id_reporte, tipo_servicio, codigo_municipio, magnitud, estado, abierto_en)
                        VALUES (:id, :reporte, :servicio, :municipio, :magnitud, :estado, :abierto)
                        ON CONFLICT (id_reporte) DO NOTHING
                        """)
                .param("id", c.idCaso())
                .param("reporte", c.idReporte())
                .param("servicio", c.tipoServicio())
                .param("municipio", c.codigoMunicipio())
                .param("magnitud", c.magnitud())
                .param("estado", CasoNuevo.ESTADO_INICIAL)
                .param("abierto", Timestamp.from(c.abiertoEn()))
                .update();
    }

    @Override
    public Optional<CasoGuardado> buscarPorReporte(UUID idReporte) {
        return jdbc.sql("SELECT * FROM atencion.casos WHERE id_reporte = :reporte")
                .param("reporte", idReporte)
                .query((rs, n) -> new CasoGuardado(
                        rs.getObject("id", UUID.class),
                        rs.getObject("id_reporte", UUID.class),
                        rs.getString("tipo_servicio"),
                        rs.getString("codigo_municipio"),
                        rs.getString("magnitud"),
                        rs.getString("estado"),
                        instante(rs, "abierto_en")))
                .optional();
    }
}
