package co.edu.javeriana.servired.identidad.contrato;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Valida eventos contra el contrato del repositorio (contratos/eventos): el sobre común y
 * el esquema de los datos de su tipo y versión (JSON Schema 2020-12), con los formatos
 * uuid y date-time comprobados. Es el mismo contrato que validan los servicios .NET.
 */
public final class ContratoEventos {

    public static final Path EVENTOS = Path.of("../../contratos/eventos").toAbsolutePath().normalize();
    private static final String PREFIJO = "https://servired.co/contratos/eventos/";

    private static final JsonSchemaFactory FABRICA = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
            b -> b.schemaMappers(m -> m.mapPrefix(PREFIJO, EVENTOS.resolve("esquemas").toUri().toString())));
    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder()
            .formatAssertionsEnabled(true).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private ContratoEventos() {
    }

    private static JsonSchema esquema(String archivo) {
        return FABRICA.getSchema(SchemaLocation.of(PREFIJO + archivo), CONFIG);
    }

    /** Errores del sobre frente al contrato; vacío si lo cumple. */
    public static List<String> errores(JsonNode sobre, String routingKey) {
        List<String> errores = new ArrayList<>();
        esquema("sobre-evento.v1.json").validate(sobre).stream()
                .map(m -> "sobre: " + m.getMessage()).forEach(errores::add);
        if (!errores.isEmpty()) {
            return errores;
        }
        String tipo = sobre.path("tipo").asText();
        int version = sobre.path("version").asInt();
        String esperado = tipo + ".v" + version;
        if (routingKey != null && !routingKey.equals(esperado)) {
            errores.add("routing key " + routingKey + "; el contrato dice " + esperado);
        }
        if (!Files.exists(EVENTOS.resolve("esquemas").resolve(esperado + ".json"))) {
            errores.add("el contrato no define " + esperado);
            return errores;
        }
        for (ValidationMessage m : esquema(esperado + ".json").validate(sobre.path("datos"))) {
            errores.add("datos: " + m.getMessage());
        }
        return errores;
    }

    /** Ejemplo del contrato: contratos/eventos/ejemplos/{tipo}.v1.{completo|minimo}.json. */
    public static JsonNode ejemplo(String tipo, String variante) {
        try {
            return JSON.readTree(EVENTOS.resolve("ejemplos").resolve(tipo + ".v1." + variante + ".json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Stream<String> variantes() {
        return Stream.of("completo", "minimo");
    }
}
