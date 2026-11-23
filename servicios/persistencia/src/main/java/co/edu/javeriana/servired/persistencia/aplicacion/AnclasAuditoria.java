package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CodificadorSegmentos;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.PublicadorAnclas;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioAuditoria;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Ancla;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.ComprobacionAncla;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.RegistroArchivado;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.Segmento;
import co.edu.javeriana.servired.persistencia.dominio.Auditoria.VerificacionAnclas;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anclas externas de la cadena de auditoría (ADR-31).
 *
 * <p>Los segmentos archivados no son inmutables en el almacén: quien pudiera escribir en el
 * bucket y en la base podría reescribir un segmento y recalcular todas las huellas siguientes, y
 * la verificación interna seguiría dando «íntegra». Por eso, cada día se fija el último hash de
 * la cadena y se publica en un lugar que el sistema no controla (un archivo en GitHub; además,
 * la API pública lo muestra para que cualquiera lo guarde). Verificar compara cada ancla
 * PUBLICADA AFUERA con el hash que hoy tiene esa secuencia, en la base o dentro de su segmento.
 */
public class AnclasAuditoria {

    private static final Logger log = LoggerFactory.getLogger(AnclasAuditoria.class);

    private final RepositorioAuditoria repositorio;
    private final AlmacenEvidencias almacen;
    private final CodificadorSegmentos codificador;
    private final PublicadorAnclas publicador;
    private final Clock reloj;

    public AnclasAuditoria(RepositorioAuditoria repositorio, AlmacenEvidencias almacen, CodificadorSegmentos codificador,
                           PublicadorAnclas publicador, Clock reloj) {
        this.repositorio = repositorio;
        this.almacen = almacen;
        this.codificador = codificador;
        this.publicador = publicador;
        this.reloj = reloj;
    }

    public boolean publicacionConfigurada() {
        return publicador.configurado();
    }

    public Optional<Ancla> anclar(ServicioCliente servicio) {
        OperacionNegocio.ANCLAR_AUDITORIA.autorizar(servicio);
        return anclar();
    }

    /**
     * Fija el ancla de hoy (si aún no existe) y la publica afuera si todavía no se pudo. No ancla
     * una cadena rota: publicaría como bueno un estado alterado.
     */
    public Optional<Ancla> anclar() {
        var verificacion = repositorio.verificar();
        if (!verificacion.integra()) {
            throw new CadenaRota("La cadena de auditoría no está íntegra (registro " + verificacion.primeraRota()
                    + ", segmento " + verificacion.segmentoRoto() + "): no se ancla un estado alterado");
        }
        var ultimo = repositorio.ultimoRegistro();
        if (ultimo.isEmpty()) {
            return Optional.empty();
        }
        LocalDate hoy = LocalDate.now(reloj.withZone(ZoneOffset.UTC));
        if (repositorio.ancla(hoy).isEmpty()) {
            repositorio.insertarAncla(new Ancla(hoy, ultimo.get().getKey(), ultimo.get().getValue(),
                    verificacion.registros(), verificacion.segmentos(), null, null, null, null));
        }
        Ancla ancla = repositorio.ancla(hoy).orElseThrow();
        if (ancla.publicadaEn() == null && publicador.configurado()) {
            String referencia = publicador.publicar(ancla);
            repositorio.marcarPublicada(hoy, reloj.instant(), publicador.destino(), referencia);
            ancla = repositorio.ancla(hoy).orElseThrow();
            log.atInfo().addKeyValue("fecha", hoy).addKeyValue("secuencia", ancla.secuencia())
                    .addKeyValue("referencia", referencia).log("ancla de auditoría publicada");
        }
        return Optional.of(ancla);
    }

    public List<Ancla> anclas(ServicioCliente servicio) {
        OperacionNegocio.CONSULTAR_AUDITORIA.autorizar(servicio);
        return repositorio.anclas();
    }

    /**
     * Compara las anclas publicadas afuera (o, si no hay destino configurado, las de la base, que
     * son menos confiables) con el hash que hoy tiene cada secuencia.
     */
    public VerificacionAnclas verificar(ServicioCliente servicio) {
        OperacionNegocio.VERIFICAR_AUDITORIA.autorizar(servicio);
        boolean externa = publicador.configurado();
        List<Ancla> anclas = externa ? publicador.publicadas() : repositorio.anclas();
        var segmentos = new HashMap<Long, Map<Long, String>>();
        var distintas = new ArrayList<ComprobacionAncla>();
        var reemplazadas = new ArrayList<ComprobacionAncla>();
        var reencadenamientos = repositorio.reencadenamientos();
        for (Ancla a : anclas) {
            var actual = hashActual(a.secuencia(), segmentos);
            boolean coincide = actual.map(h -> h[0].equals(a.hash())).orElse(false);
            if (coincide) {
                continue;
            }
            // Un reencadenamiento autorizado, del mismo día del ancla o posterior, que empieza en o
            // antes de su secuencia la reemplazó (ADR-36): se informa, pero no es una reescritura.
            var causa = reencadenamientos.stream()
                    .filter(r -> r.desdeSecuencia() <= a.secuencia()
                            && !a.fecha().isAfter(r.realizadoEn().atZone(java.time.ZoneOffset.UTC).toLocalDate()))
                    .findFirst();
            var comprobacion = new ComprobacionAncla(a.fecha(), a.secuencia(), a.hash(), actual.map(h -> h[0]).orElse(null),
                    causa.map(r -> "reemplazada por el reencadenamiento " + r.migracion() + ": " + r.motivo())
                            .orElseGet(() -> actual.map(h -> h[1]).orElse("no se encontró el registro")), false);
            (causa.isPresent() ? reemplazadas : distintas).add(comprobacion);
        }
        if (!distintas.isEmpty()) {
            log.atError().addKeyValue("distintas", distintas.size())
                    .log("la cadena de auditoría no coincide con anclas publicadas: posible reescritura de la historia");
        }
        return new VerificacionAnclas(externa ? publicador.destino() : "base de datos (sin destino externo configurado)",
                anclas.size(), distintas.isEmpty(), distintas, reemplazadas);
    }

    /** [hash, ubicación] del registro con esa secuencia: en la base o dentro de su segmento. */
    private Optional<String[]> hashActual(long secuencia, Map<Long, Map<Long, String>> cache) {
        var enBase = repositorio.hashEnBase(secuencia);
        if (enBase.isPresent()) {
            return Optional.of(new String[] {enBase.get(), "base"});
        }
        Optional<Segmento> segmento = repositorio.segmentoQueCubre(secuencia);
        if (segmento.isEmpty()) {
            return Optional.empty();
        }
        Segmento s = segmento.get();
        var hashes = cache.computeIfAbsent(s.id(), id -> almacen.leer(s.claveObjeto())
                .map(archivo -> {
                    var m = new HashMap<Long, String>();
                    for (RegistroArchivado r : codificador.decodificar(archivo)) {
                        m.put(r.secuencia(), r.hash());
                    }
                    return (Map<Long, String>) m;
                }).orElse(Map.of()));
        String hash = hashes.get(secuencia);
        return hash == null ? Optional.empty() : Optional.of(new String[] {hash, "segmento " + s.id()});
    }

    /** La cadena interna ya está rota: no se ancla. */
    public static class CadenaRota extends RuntimeException {
        public CadenaRota(String mensaje) {
            super(mensaje);
        }
    }
}
