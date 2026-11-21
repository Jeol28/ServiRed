package co.edu.javeriana.servired.persistencia.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.infraestructura.config.ClavesServicio;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClavesServicioTest {

    private final ClavesServicio claves = new ClavesServicio(Map.of(
            ServicioCliente.REPORTES, "clave-reportes-1234567",
            ServicioCliente.ATENCION, "corta",
            ServicioCliente.INDICADORES, ""));

    @Test
    void identificaAlServicioPorSuClave() {
        assertThat(claves.identificar("clave-reportes-1234567")).contains(ServicioCliente.REPORTES);
    }

    @Test
    void rechazaClavesDesconocidasOVacias() {
        assertThat(claves.identificar("otra-clave-cualquiera")).isEmpty();
        assertThat(claves.identificar("")).isEmpty();
        assertThat(claves.identificar(null)).isEmpty();
    }

    @Test
    void ignoraClavesConfiguradasDemasiadoCortas() {
        assertThat(claves.identificar("corta")).isEmpty();
    }
}
