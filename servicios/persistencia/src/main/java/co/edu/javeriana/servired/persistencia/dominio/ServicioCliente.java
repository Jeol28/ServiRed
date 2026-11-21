package co.edu.javeriana.servired.persistencia.dominio;

/** Servicios internos que pueden invocar a Persistencia, cada uno con su clave. */
public enum ServicioCliente {
    IDENTIDAD, REPORTES, ATENCION, INDICADORES;

    public String nombre() {
        return name().toLowerCase();
    }
}
