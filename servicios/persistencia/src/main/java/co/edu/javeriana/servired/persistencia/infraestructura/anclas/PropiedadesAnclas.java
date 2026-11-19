package co.edu.javeriana.servired.persistencia.infraestructura.anclas;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Destino externo de las anclas (ADR-31): un repositorio de GitHub. Sin repositorio o sin token,
 * las anclas solo quedan en la base y en la API pública.
 *
 * @param token token de grano fino con «Contents: read and write» solo sobre ese repositorio
 */
@ConfigurationProperties(prefix = "servired.auditoria.anclas.github")
public record PropiedadesAnclas(String repositorio, String rama, String ruta, String token, String api) {

    public PropiedadesAnclas {
        rama = rama == null || rama.isBlank() ? "anclas-auditoria" : rama;
        ruta = ruta == null || ruta.isBlank() ? "auditoria/anclas.jsonl" : ruta;
        api = api == null || api.isBlank() ? "https://api.github.com" : api.replaceAll("/+$", "");
    }
}
