package co.edu.javeriana.servired.reportes.dominio;

import java.util.List;

/** El reporte no cumple las reglas de validación (RNF-DAT1). */
public class ReporteInvalido extends RuntimeException {

    private final List<ErrorDeCampo> errores;

    public ReporteInvalido(List<ErrorDeCampo> errores) {
        super("Reporte inválido: " + errores);
        this.errores = List.copyOf(errores);
    }

    public List<ErrorDeCampo> errores() {
        return errores;
    }
}
