package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CodificadorSegmentos;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioAuditoria;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Rango;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroArchivado;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.ResultadoArchivo;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Segmento;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.VerificacionArchivo;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Archivado del registro de auditoría conservando la cadena de huellas (ADR-29).
 *
 * <p>Los meses completos más viejos que {@code mesesEnBase} salen de la base en segmentos
 * (de a lo sumo {@code maximoPorSegmento} registros) hacia el almacén de objetos, bajo
 * {@code auditoria/segmentos/}. Para cada segmento:
 * <ol>
 * <li>Lee el rango y comprueba en la base que su cadena está íntegra y enlaza con el último
 * segmento: nunca archiva un tramo alterado (se detiene y lo informa).</li>
 * <li>Lo sube y lo vuelve a descargar para confirmar que el almacén guardó exactamente esos
 * bytes (SHA-256).</li>
 * <li>En una transacción inserta el segmento en auditoria.segmentos y borra de la base los
 * registros que cubre (el trigger solo lo permite si un segmento ya los cubre).</li>
 * </ol>
 * Si falla entre la subida y la transacción, el archivo queda con la misma clave y el siguiente
 * intento lo sobrescribe. Nada se destruye: los registros siguen en el archivo.
 */
public class ArchivoAuditoria {

    static final String PREFIJO = "auditoria/segmentos/";
    private static final int MAXIMO_SEGMENTOS_POR_EJECUCION = 24;
    private static final Logger log = LoggerFactory.getLogger(ArchivoAuditoria.class);

    private final UnidadDeTrabajo transaccion;
    private final RepositorioAuditoria repositorio;
    private final AlmacenEvidencias almacen;
    private final CodificadorSegmentos codificador;
    private final Clock reloj;
    private final int mesesEnBase;
    private final int maximoPorSegmento;

    public ArchivoAuditoria(UnidadDeTrabajo transaccion, RepositorioAuditoria repositorio, AlmacenEvidencias almacen,
                            CodificadorSegmentos codificador, Clock reloj, int mesesEnBase, int maximoPorSegmento) {
        this.transaccion = transaccion;
        this.repositorio = repositorio;
        this.almacen = almacen;
        this.codificador = codificador;
        this.reloj = reloj;
        this.mesesEnBase = mesesEnBase;
        this.maximoPorSegmento = maximoPorSegmento;
    }

    /** Inicio (UTC) del mes más viejo que se conserva en la base: lo anterior se archiva. */
    public Instant limitePorDefecto() {
        return YearMonth.now(reloj.withZone(ZoneOffset.UTC)).minusMonths(mesesEnBase).atDay(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public ResultadoArchivo archivar(ServicioCliente servicio, Instant limite) {
        OperacionNegocio.ARCHIVAR_AUDITORIA.autorizar(servicio);
        return archivar(limite == null ? limitePorDefecto() : limite);
    }

    public ResultadoArchivo archivar(Instant limite) {
        int segmentos = 0;
        long registros = 0;
        while (segmentos < MAXIMO_SEGMENTOS_POR_EJECUCION) {
            var rango = repositorio.proximoRango(limite, maximoPorSegmento);
            if (rango.isEmpty()) {
                break;
            }
            String anterior = repositorio.ultimoSegmento().map(Segmento::hashFinal).orElse(null);
            List<RegistroArchivado> filas = repositorio.leer(rango.get());
            if (filas.isEmpty()) {
                break;
            }
            Long rota = repositorio.primeraRota(filas, anterior);
            if (rota != null) {
                String motivo = "la cadena está rota en la secuencia " + rota + ": no se archiva un tramo alterado";
                log.atError().addKeyValue("secuencia", rota).log("archivado de auditoría detenido: " + motivo);
                return new ResultadoArchivo(segmentos, registros, limite, motivo);
            }
            Segmento segmento = subir(rango.get(), filas, anterior);
            int borrados = transaccion.ejecutar(() -> repositorio.archivar(segmento));
            if (borrados != filas.size()) {
                throw new IllegalStateException("Se esperaban " + filas.size() + " registros y se borraron " + borrados);
            }
            segmentos++;
            registros += borrados;
            log.atInfo().addKeyValue("desde", segmento.secuenciaDesde()).addKeyValue("hasta", segmento.secuenciaHasta())
                    .addKeyValue("registros", borrados).addKeyValue("clave", segmento.claveObjeto())
                    .log("segmento de auditoría archivado");
        }
        return new ResultadoArchivo(segmentos, registros, limite, null);
    }

    private Segmento subir(Rango rango, List<RegistroArchivado> filas, String anterior) {
        byte[] archivo = codificador.codificar(filas);
        String sha = sha256(archivo);
        var primero = filas.getFirst();
        var ultimo = filas.getLast();
        String clave = PREFIJO + "%012d-%012d.jsonl.gz".formatted(primero.secuencia(), ultimo.secuencia());
        almacen.guardar(clave, "application/gzip", archivo);
        byte[] guardado = almacen.leer(clave).orElseThrow(() -> new IllegalStateException("El almacén no devolvió " + clave));
        if (!Arrays.equals(archivo, guardado)) {
            throw new IllegalStateException("El almacén guardó un archivo distinto para " + clave);
        }
        return new Segmento(null, primero.secuencia(), ultimo.secuencia(), filas.size(), anterior, ultimo.hash(),
                Instant.parse(filas.stream().map(RegistroArchivado::ocurridoEn).min(String::compareTo).orElseThrow()),
                Instant.parse(filas.stream().map(RegistroArchivado::ocurridoEn).max(String::compareTo).orElseThrow()),
                clave, sha, archivo.length, null);
    }

    /** Descarga cada segmento, compara su SHA-256 con el índice y recalcula todas sus huellas y enlaces. */
    public VerificacionArchivo verificar(ServicioCliente servicio) {
        OperacionNegocio.VERIFICAR_AUDITORIA.autorizar(servicio);
        var segmentos = repositorio.segmentos();
        String anterior = null;
        long registros = 0;
        for (Segmento s : segmentos) {
            String problema = problema(s, anterior);
            if (problema != null) {
                return new VerificacionArchivo(segmentos.size(), registros, false, s.id(), problema);
            }
            registros += s.registros();
            anterior = s.hashFinal();
        }
        return new VerificacionArchivo(segmentos.size(), registros, true, null, null);
    }

    private String problema(Segmento s, String anterior) {
        if (!java.util.Objects.equals(s.hashAnteriorInicial(), anterior)) {
            return "no enlaza con el segmento anterior";
        }
        var archivo = almacen.leer(s.claveObjeto());
        if (archivo.isEmpty()) {
            return "falta el archivo " + s.claveObjeto();
        }
        if (!sha256(archivo.get()).equals(s.sha256Archivo())) {
            return "el archivo " + s.claveObjeto() + " cambió (SHA-256 distinto)";
        }
        List<RegistroArchivado> filas = codificador.decodificar(archivo.get());
        if (filas.size() != s.registros() || filas.getFirst().secuencia() != s.secuenciaDesde()
                || filas.getLast().secuencia() != s.secuenciaHasta() || !filas.getLast().hash().equals(s.hashFinal())) {
            return "el contenido no coincide con el índice del segmento";
        }
        Long rota = repositorio.primeraRota(filas, anterior);
        return rota == null ? null : "la huella del registro " + rota + " no coincide";
    }

    static String sha256(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
