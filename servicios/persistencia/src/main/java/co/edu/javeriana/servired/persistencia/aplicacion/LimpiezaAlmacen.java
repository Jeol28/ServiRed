package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4;
import co.edu.javeriana.servired.persistencia.dominio.ObjetoAlmacenado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Borra del almacén las fotos que ninguna evidencia referencia (objetos huérfanos).
 *
 * <p>Se forman cuando la foto se subió (fuera de la transacción) y la transacción que
 * guardaba su referencia falló, y además falló la compensación que la borraba, o el
 * proceso se cayó entre las dos. Nadie puede verlas ni pedirlas, pero ocupan espacio y,
 * en las denuncias anónimas, conservan una foto sin motivo (minimización de datos).
 *
 * <p>Salvaguardas:
 * <ul>
 * <li>Solo mira los prefijos que escribe Persistencia ({@code reportes/}, {@code denuncias/}).</li>
 * <li>Respeta un período de gracia: un objeto reciente puede tener su transacción en curso.</li>
 * <li>Freno: si los huérfanos son demasiados respecto a lo revisado, no borra nada. Eso
 * no es un residuo normal sino, casi seguro, una configuración cruzada (por ejemplo, una
 * base vacía o restaurada junto a un bucket con fotos), y borrar sería irreversible.</li>
 * <li>Vuelve a comprobar la referencia justo antes de borrar cada objeto.</li>
 * <li>No abre transacciones mientras lista o borra.</li>
 * </ul>
 * Con varias réplicas puede correr a la vez en más de una: borrar dos veces el mismo
 * objeto no tiene efecto.
 */
public class LimpiezaAlmacen {

    static final List<String> PREFIJOS = List.of("reportes/", "denuncias/");

    private static final Logger log = LoggerFactory.getLogger(LimpiezaAlmacen.class);

    private final AlmacenEvidencias almacen;
    private final RepositorioFase4 repositorio;
    private final Clock reloj;
    private final Duration gracia;
    private final int frenoMinimo;
    private final double frenoProporcion;

    public LimpiezaAlmacen(AlmacenEvidencias almacen, RepositorioFase4 repositorio, Clock reloj, Duration gracia,
                           int frenoMinimo, double frenoProporcion) {
        this.almacen = almacen;
        this.repositorio = repositorio;
        this.reloj = reloj;
        this.gracia = gracia;
        this.frenoMinimo = frenoMinimo;
        this.frenoProporcion = frenoProporcion;
    }

    public Resultado ejecutar() {
        Instant limite = reloj.instant().minus(gracia);
        var huerfanos = new ArrayList<String>();
        int[] revisados = {0};
        int[] recientes = {0};
        for (String prefijo : PREFIJOS) {
            almacen.recorrer(prefijo, pagina -> {
                revisados[0] += pagina.size();
                List<String> viejos = pagina.stream().filter(o -> o.modificado().isBefore(limite))
                        .map(ObjetoAlmacenado::clave).toList();
                recientes[0] += pagina.size() - viejos.size();
                Set<String> referenciados = repositorio.clavesReferenciadas(viejos);
                viejos.stream().filter(c -> !referenciados.contains(c)).forEach(huerfanos::add);
            });
        }
        if (huerfanos.size() >= frenoMinimo && huerfanos.size() > frenoProporcion * revisados[0]) {
            log.atError().addKeyValue("revisados", revisados[0]).addKeyValue("huerfanos", huerfanos.size())
                    .log("limpieza del almacén FRENADA: demasiados objetos sin referencia; no se borró nada. "
                            + "Revise que la base y el bucket correspondan al mismo entorno");
            return new Resultado(revisados[0], recientes[0], huerfanos.size(), 0, 0, true);
        }
        int borrados = 0;
        int fallidos = 0;
        for (String clave : huerfanos) {
            if (repositorio.existeClaveEvidencia(clave)) {
                continue;   // se referenció mientras tanto
            }
            try {
                almacen.eliminar(clave);
                borrados++;
            } catch (RuntimeException e) {
                fallidos++;
                log.atWarn().addKeyValue("clave", clave).log("no se pudo borrar el objeto huérfano: " + e.getMessage());
            }
        }
        return new Resultado(revisados[0], recientes[0], huerfanos.size(), borrados, fallidos, false);
    }

    /**
     * @param recientes objetos dentro del período de gracia (no se evaluaron)
     * @param frenada   true si no se borró nada por exceder el freno
     */
    public record Resultado(int revisados, int recientes, int huerfanos, int borrados, int fallidos, boolean frenada) {
    }
}
