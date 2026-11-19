package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CodificadorSegmentos;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroArchivado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Component;

/**
 * Segmento de auditoría como JSON Lines comprimido (ADR-29). La primera línea describe el
 * segmento; cada línea siguiente es un registro con sus campos tal como los usa
 * auditoria.huella, así cualquiera puede recalcular la cadena sin SERVI-RED.
 */
@Component
public class CodificadorSegmentosJson implements CodificadorSegmentos {

    static final String FORMATO = "servired-auditoria-segmento";
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public byte[] codificar(List<RegistroArchivado> registros) {
        var salida = new ByteArrayOutputStream();
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(salida), StandardCharsets.UTF_8)) {
            var cabecera = new LinkedHashMap<String, Object>();
            cabecera.put("formato", FORMATO);
            cabecera.put("version", 1);
            cabecera.put("registros", registros.size());
            cabecera.put("secuenciaDesde", registros.getFirst().secuencia());
            cabecera.put("secuenciaHasta", registros.getLast().secuencia());
            cabecera.put("hashAnteriorInicial", registros.getFirst().hashAnterior());
            cabecera.put("hashFinal", registros.getLast().hash());
            cabecera.put("huella", "sha256(hashAnterior|idEvento|tipo|origen|categoria|accion|resultado|actorId|"
                    + "actorRol|recursoTipo|recursoId|detalle|ocurridoEn), con '' en lugar de null; ver V9__auditoria.sql");
            w.write(json.writeValueAsString(cabecera));
            w.write('\n');
            for (RegistroArchivado r : registros) {
                w.write(json.writeValueAsString(r));
                w.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return salida.toByteArray();
    }

    @Override
    public List<RegistroArchivado> decodificar(byte[] archivo) {
        var filas = new ArrayList<RegistroArchivado>();
        try (var lector = new BufferedReader(new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(archivo)),
                StandardCharsets.UTF_8))) {
            String linea;
            while ((linea = lector.readLine()) != null) {
                if (linea.isBlank()) {
                    continue;
                }
                JsonNode nodo = json.readTree(linea);
                if (nodo.has("formato")) {
                    continue;
                }
                filas.add(json.treeToValue(nodo, RegistroArchivado.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Segmento ilegible", e);
        }
        return filas;
    }
}
