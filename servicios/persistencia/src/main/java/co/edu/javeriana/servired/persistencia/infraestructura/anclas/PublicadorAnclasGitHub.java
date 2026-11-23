package co.edu.javeriana.servired.persistencia.infraestructura.anclas;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.PublicadorAnclas;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Ancla;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica las anclas en un archivo JSON Lines de un repositorio de GitHub, en una rama propia
 * (por defecto {@code anclas-auditoria}, creada desde la rama principal si no existe), con la
 * API de contenidos: cada ancla es un commit. GitHub queda fuera del control del sistema: el
 * token solo puede escribir en ese repositorio, y la protección de la rama (sin force push ni
 * borrado, configurada en GitHub) impide reescribir lo publicado.
 */
public class PublicadorAnclasGitHub implements PublicadorAnclas {

    private final PropiedadesAnclas p;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public PublicadorAnclasGitHub(PropiedadesAnclas propiedades) {
        this.p = propiedades;
    }

    @Override
    public boolean configurado() {
        return p.repositorio() != null && !p.repositorio().isBlank() && p.token() != null && !p.token().isBlank();
    }

    @Override
    public String destino() {
        return "github:" + p.repositorio() + "@" + p.rama() + ":" + p.ruta();
    }

    @Override
    public String publicar(Ancla a) {
        asegurarRama();
        var actual = leerArchivo();
        String contenido = actual == null ? "" : actual.contenido();
        String fecha = a.fecha().toString();
        for (String linea : contenido.split("\n")) {
            if (!linea.isBlank() && fecha.equals(leer(linea).path("fecha").asText())) {
                return actual.url();   // ya publicada (p. ej. un reintento): idempotente
            }
        }
        var linea = new LinkedHashMap<String, Object>();
        linea.put("fecha", fecha);
        linea.put("secuencia", a.secuencia());
        linea.put("hash", a.hash());
        linea.put("registros", a.registros());
        linea.put("segmentos", a.segmentos());
        linea.put("sistema", "SERVI-RED");
        String nuevo = contenido + (contenido.isEmpty() || contenido.endsWith("\n") ? "" : "\n") + escribir(linea) + "\n";
        var cuerpo = new LinkedHashMap<String, Object>();
        cuerpo.put("message", "auditoría: ancla " + fecha + " (secuencia " + a.secuencia() + ")");
        cuerpo.put("content", Base64.getEncoder().encodeToString(nuevo.getBytes(StandardCharsets.UTF_8)));
        cuerpo.put("branch", p.rama());
        if (actual != null) {
            cuerpo.put("sha", actual.sha());
        }
        JsonNode r = enviar("PUT", "/repos/" + p.repositorio() + "/contents/" + p.ruta(), cuerpo, 200, 201);
        return r.path("commit").path("html_url").asText(r.path("commit").path("sha").asText());
    }

    @Override
    public List<Ancla> publicadas() {
        var actual = leerArchivo();
        var anclas = new ArrayList<Ancla>();
        if (actual == null) {
            return anclas;
        }
        for (String linea : actual.contenido().split("\n")) {
            if (linea.isBlank()) {
                continue;
            }
            JsonNode n = leer(linea);
            anclas.add(new Ancla(LocalDate.parse(n.path("fecha").asText()), n.path("secuencia").asLong(),
                    n.path("hash").asText(), n.path("registros").asLong(), n.path("segmentos").asInt(), null, null,
                    destino(), null));
        }
        return anclas;
    }

    private record Archivo(String contenido, String sha, String url) {
    }

    private Archivo leerArchivo() {
        var r = pedir("GET", "/repos/" + p.repositorio() + "/contents/" + p.ruta() + "?ref="
                + URLEncoder.encode(p.rama(), StandardCharsets.UTF_8), null);
        if (r.statusCode() == 404) {
            return null;
        }
        JsonNode n = verificar(r, 200);
        String contenido = new String(Base64.getMimeDecoder().decode(n.path("content").asText()), StandardCharsets.UTF_8);
        return new Archivo(contenido, n.path("sha").asText(), n.path("html_url").asText());
    }

    private void asegurarRama() {
        if (pedir("GET", "/repos/" + p.repositorio() + "/branches/" + p.rama(), null).statusCode() == 200) {
            return;
        }
        String principal = verificar(pedir("GET", "/repos/" + p.repositorio(), null), 200).path("default_branch").asText();
        String sha = verificar(pedir("GET", "/repos/" + p.repositorio() + "/git/ref/heads/" + principal, null), 200)
                .path("object").path("sha").asText();
        enviar("POST", "/repos/" + p.repositorio() + "/git/refs", Map.of("ref", "refs/heads/" + p.rama(), "sha", sha),
                201);
    }

    private JsonNode enviar(String metodo, String ruta, Object cuerpo, int... esperados) {
        return verificar(pedir(metodo, ruta, escribir(cuerpo)), esperados);
    }

    private HttpResponse<String> pedir(String metodo, String ruta, String cuerpo) {
        var b = HttpRequest.newBuilder(URI.create(p.api() + ruta)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + p.token())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "servired-auditoria");
        b = cuerpo == null ? b.method(metodo, HttpRequest.BodyPublishers.noBody())
                : b.header("Content-Type", "application/json").method(metodo, HttpRequest.BodyPublishers.ofString(cuerpo));
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("GitHub no respondió: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido", e);
        }
    }

    private JsonNode verificar(HttpResponse<String> r, int... esperados) {
        for (int e : esperados) {
            if (r.statusCode() == e) {
                return leer(r.body().isBlank() ? "{}" : r.body());
            }
        }
        // Sin el cuerpo completo: podría traer detalles del repositorio; el código basta para diagnosticar.
        throw new IllegalStateException("GitHub respondió " + r.statusCode() + " a " + r.request().method() + " "
                + r.uri().getPath());
    }

    private JsonNode leer(String texto) {
        try {
            return json.readTree(texto);
        } catch (IOException e) {
            throw new IllegalStateException("Respuesta ilegible de GitHub", e);
        }
    }

    private String escribir(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
