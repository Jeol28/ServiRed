package co.edu.javeriana.servired.identidad.aplicacion.puertos;

/** Hash de claves con un algoritmo lento y con sal (bcrypt en la infraestructura). */
public interface CifradorClaves {

    String cifrar(String clave);

    boolean coincide(String clave, String hash);
}
