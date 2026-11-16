package co.edu.javeriana.servired.identidad.infraestructura.web;

import co.edu.javeriana.servired.identidad.aplicacion.Autenticacion.Sesion;
import co.edu.javeriana.servired.identidad.dominio.Cuenta;
import co.edu.javeriana.servired.identidad.dominio.Rol;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Esquemas de la API pública de identidad (contratos/api-publica/servired-api.v1.yaml). */
final class Dtos {

    private Dtos() {
    }

    record Registro(String correo, String clave, String nombre, Boolean aceptaTratamientoDatos) {
    }

    record Login(String correo, String clave) {
    }

    record Refresco(String tokenRefresco) {
    }

    record NuevaCuentaPersonal(String correo, String clave, String nombre, Rol rol, UUID idEmpresa) {
    }

    record CambiosAdministrador(String nombre, Boolean activa, UUID idEmpresa) {
    }

    record CambiosPropios(String nombre, String claveActual, String claveNueva) {
    }

    record CuentaDto(UUID idCuenta, String correo, Rol rol, UUID idEmpresa, String nombre, boolean activa,
                     Instant creadaEn) {
        static CuentaDto de(Cuenta c) {
            return new CuentaDto(c.idCuenta(), c.correo().valor(), c.rol(), c.idEmpresa(), c.nombre(), c.activa(),
                    c.creadaEn());
        }
    }

    record ResumenCuenta(UUID idCuenta, Rol rol, String nombre, UUID idEmpresa) {
    }

    record SesionDto(String tokenAcceso, String tipoToken, long expiraEn, String tokenRefresco,
                     ResumenCuenta cuenta) {
        static SesionDto de(Sesion s, Clock reloj) {
            long segundos = Math.max(0, Duration.between(reloj.instant(), s.acceso().expiraEn()).toSeconds());
            return new SesionDto(s.acceso().valor(), "Bearer", segundos, s.tokenRefresco(),
                    new ResumenCuenta(s.idCuenta(), s.rol(), s.nombre(), s.idEmpresa()));
        }
    }

    record PaginaDto(List<CuentaDto> elementos, long total, int pagina, int tamano) {
    }
}
