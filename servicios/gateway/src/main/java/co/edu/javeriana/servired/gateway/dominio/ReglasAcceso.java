package co.edu.javeriana.servired.gateway.dominio;

import java.util.List;
import java.util.Set;

/**
 * Matriz de control de acceso del gateway (RNF-SEG1, RNF-SEG2): primera
 * barrera, gruesa, por ruta y método. Cada servicio vuelve a validar el JWT y
 * aplica sus reglas finas (defensa en profundidad). Se evalúa en orden; lo que
 * no aparece se niega.
 */
public final class ReglasAcceso {

    public enum Acceso { PUBLICO, AUTENTICADO, ROLES }

    /** @param metodo método HTTP, o null para todos */
    public record Regla(String metodo, List<String> rutas, Acceso acceso, Set<String> roles) {

        static Regla publica(String metodo, String... rutas) {
            return new Regla(metodo, List.of(rutas), Acceso.PUBLICO, Set.of());
        }

        static Regla autenticada(String metodo, String... rutas) {
            return new Regla(metodo, List.of(rutas), Acceso.AUTENTICADO, Set.of());
        }

        static Regla roles(String metodo, Set<String> roles, String... rutas) {
            return new Regla(metodo, List.of(rutas), Acceso.ROLES, roles);
        }
    }

    public static final String CIUDADANO = "CIUDADANO";
    public static final String OPERADOR = "OPERADOR";
    public static final String REGULADOR = "REGULADOR";
    public static final String ADMINISTRADOR = "ADMINISTRADOR";

    public static final List<Regla> TABLA = List.of(
            // Salud pública del gateway
            Regla.publica("GET", "/livez", "/readyz"),
            // Autenticación
            Regla.publica("POST", "/api/v1/auth/registro", "/api/v1/auth/login", "/api/v1/auth/refrescar",
                    "/api/v1/auth/cerrar-sesion"),
            // Denuncias anónimas (RF-5): sin token; el gateway descarta Authorization (ADR-09)
            Regla.publica("POST", "/api/v1/denuncias", "/api/v1/denuncias/**"),
            // Indicadores restringidos (van antes de las reglas públicas: gana la primera que coincide)
            Regla.roles("GET", Set.of(REGULADOR, ADMINISTRADOR), "/api/v1/indicadores/exportaciones/**"),
            Regla.roles("GET", Set.of(OPERADOR, REGULADOR, ADMINISTRADOR), "/api/v1/mapas/operativo", "/api/v1/mapas/operativo/**"),
            // Portal público: mapas, indicadores y datos abiertos (solo lectura y solo agregados)
            Regla.publica("GET", "/api/v1/indicadores/**", "/api/v1/mapas/**", "/api/v1/datos-abiertos/**"),
            // Reportes ciudadanos (RF-1 a RF-4)
            Regla.roles(null, Set.of(CIUDADANO), "/api/v1/reportes", "/api/v1/reportes/**"),
            // Casos (RF-6, RF-7): el regulador solo consulta
            Regla.roles("GET", Set.of(OPERADOR, REGULADOR, ADMINISTRADOR), "/api/v1/casos", "/api/v1/casos/**"),
            Regla.roles(null, Set.of(OPERADOR, ADMINISTRADOR), "/api/v1/casos", "/api/v1/casos/**"),
            // Moderación de denuncias anónimas (RF-5): el regulador solo consulta
            Regla.roles("GET", Set.of(OPERADOR, REGULADOR, ADMINISTRADOR), "/api/v1/moderacion/**"),
            Regla.roles(null, Set.of(OPERADOR, ADMINISTRADOR), "/api/v1/moderacion/**"),
            // Anclas de la cadena de auditoría (ADR-31): públicas, para que cualquiera las guarde afuera
            Regla.publica("GET", "/api/v1/auditoria/anclas"),
            // Auditoría (RF-12): solo lectura; el webhook interno de alertas nunca se enruta
            Regla.roles("GET", Set.of(REGULADOR, ADMINISTRADOR), "/api/v1/auditoria/**"),
            // Cuentas (RF-11)
            Regla.autenticada(null, "/api/v1/cuentas/yo"),
            Regla.roles(null, Set.of(ADMINISTRADOR), "/api/v1/cuentas", "/api/v1/cuentas/**"));

    private ReglasAcceso() {
    }
}
