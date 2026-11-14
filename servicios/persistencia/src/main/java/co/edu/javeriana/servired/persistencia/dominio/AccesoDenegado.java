package co.edu.javeriana.servired.persistencia.dominio;

/** Un servicio intentó una operación que no le corresponde. */
public class AccesoDenegado extends RuntimeException {

    public AccesoDenegado(ServicioCliente servicio, OperacionNegocio operacion) {
        super("El servicio " + servicio.nombre() + " no puede invocar " + operacion.nombre());
    }

    public AccesoDenegado(String mensaje) {
        super(mensaje);
    }
}
