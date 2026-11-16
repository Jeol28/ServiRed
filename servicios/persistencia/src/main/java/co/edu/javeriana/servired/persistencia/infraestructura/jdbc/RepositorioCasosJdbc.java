package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioCasos;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.CasoNuevo;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Apertura de casos (esquema atencion). Un caso por reporte. */
@Component
public class RepositorioCasosJdbc implements RepositorioCasos {

    private final JdbcClient jdbc;
    private final RepositorioAtencionJdbc atencion;

    public RepositorioCasosJdbc(JdbcClient jdbc, RepositorioAtencionJdbc atencion) {
        this.jdbc = jdbc;
        this.atencion = atencion;
    }

    @Override
    public void insertarSiNoExiste(CasoNuevo c) {
        int insertados = jdbc.sql("""
                        INSERT INTO atencion.casos
                            (id, id_reporte, tipo_servicio, codigo_municipio, magnitud, estado, abierto_en,
                             id_empresa, prioridad, latitud, longitud, descripcion, inicio_estimado)
                        VALUES (:id, :reporte, :servicio, :municipio, :magnitud, :estado, :abierto,
                                :empresa, :prioridad, :lat, :lon, :descripcion, :inicio)
                        ON CONFLICT (id_reporte) DO NOTHING
                        """)
                .param("id", c.idCaso()).param("reporte", c.idReporte()).param("servicio", c.tipoServicio())
                .param("municipio", c.codigoMunicipio()).param("magnitud", c.magnitud())
                .param("estado", CasoNuevo.ESTADO_INICIAL).param("abierto", Timestamp.from(c.abiertoEn()))
                .param("empresa", c.idEmpresa()).param("prioridad", c.prioridad()).param("lat", c.latitud())
                .param("lon", c.longitud()).param("descripcion", c.descripcion())
                .param("inicio", c.inicioEstimado() == null ? null : Timestamp.from(c.inicioEstimado()))
                .update();
        if (insertados == 1) {
            atencion.agregarHistoria(c.idCaso(), CasoNuevo.ESTADO_INICIAL, c.abiertoEn(),
                    c.idEmpresa() == null ? "Caso abierto sin empresa con cobertura; requiere enrutamiento manual"
                            : "Caso abierto y enrutado a la empresa con cobertura", null);
        }
    }

    @Override
    public Optional<CasoGuardado> buscarPorReporte(UUID idReporte) {
        return jdbc.sql("SELECT id FROM atencion.casos WHERE id_reporte = :r").param("r", idReporte)
                .query(UUID.class).optional().flatMap(atencion::caso);
    }
}
