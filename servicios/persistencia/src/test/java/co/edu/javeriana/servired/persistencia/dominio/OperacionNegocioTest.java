package co.edu.javeriana.servired.persistencia.dominio;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OperacionNegocioTest {

    @Test
    void reportesPuedeGuardarReportes() {
        assertThatCode(() -> OperacionNegocio.GUARDAR_REPORTE.autorizar(ServicioCliente.REPORTES))
                .doesNotThrowAnyException();
    }

    @Test
    void atencionNoPuedeGuardarReportes() {
        assertThatThrownBy(() -> OperacionNegocio.GUARDAR_REPORTE.autorizar(ServicioCliente.ATENCION))
                .isInstanceOf(AccesoDenegado.class);
    }

    @Test
    void reportesNoPuedeAbrirCasos() {
        assertThatThrownBy(() -> OperacionNegocio.ABRIR_CASO.autorizar(ServicioCliente.REPORTES))
                .isInstanceOf(AccesoDenegado.class);
    }
}
