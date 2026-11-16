package co.edu.javeriana.servired.identidad.infraestructura.seguridad;

import co.edu.javeriana.servired.identidad.aplicacion.puertos.CifradorClaves;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** bcrypt con costo configurable (12 por defecto). La clave en claro no sale de Identidad. */
public class CifradorBcrypt implements CifradorClaves {

    private final BCryptPasswordEncoder bcrypt;

    public CifradorBcrypt(int costo) {
        this.bcrypt = new BCryptPasswordEncoder(costo);
    }

    @Override
    public String cifrar(String clave) {
        return bcrypt.encode(clave);
    }

    @Override
    public boolean coincide(String clave, String hash) {
        try {
            return bcrypt.matches(clave, hash);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
