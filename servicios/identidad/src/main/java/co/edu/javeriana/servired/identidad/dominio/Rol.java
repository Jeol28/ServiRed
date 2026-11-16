package co.edu.javeriana.servired.identidad.dominio;

/** Roles de SERVI-RED (RNF-SEG2). Cada cuenta tiene exactamente uno. */
public enum Rol {
    CIUDADANO, OPERADOR, REGULADOR, ADMINISTRADOR;

    /** Un operador pertenece a una empresa prestadora; los demás roles no. */
    public boolean requiereEmpresa() {
        return this == OPERADOR;
    }
}
