package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioIndicadores;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.ActualizacionVista;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Agregado;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Agrupacion;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.EstadoVista;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.PaginaHechos;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;
import java.time.Duration;
import java.util.List;

/**
 * Vista de lectura de Indicadores (SAD: "actualizarVistaMapa", "guardarIndicador").
 * La proyección es idempotente por naturaleza: un upsert con los mismos datos no
 * cambia nada, y los datos más antiguos nunca pisan a los más recientes.
 */
public class OperacionesIndicadores {

    public static final Duration PERIODO_MAXIMO = Duration.ofDays(400);

    private final UnidadDeTrabajo transaccion;
    private final RepositorioIndicadores repositorio;

    public OperacionesIndicadores(UnidadDeTrabajo transaccion, RepositorioIndicadores repositorio) {
        this.transaccion = transaccion;
        this.repositorio = repositorio;
    }

    public void actualizarVista(ServicioCliente s, ActualizacionVista a) {
        OperacionNegocio.ACTUALIZAR_VISTA.autorizar(s);
        transaccion.ejecutar(() -> {
            repositorio.actualizar(a);
            return null;
        });
    }

    public List<Agregado> agregados(ServicioCliente s, Filtro filtro, Agrupacion agrupacion, double celdaGrados,
                                    double celdaRuralGrados) {
        OperacionNegocio.CALCULAR_AGREGADOS.autorizar(s);
        validarPeriodo(filtro);
        if (agrupacion == Agrupacion.CELDA && (celdaGrados < 0.001 || celdaGrados > 1
                || celdaRuralGrados < celdaGrados || celdaRuralGrados > 1)) {
            throw new SolicitudRechazada("Tamaño de celda fuera de rango (la rural no puede ser menor que la urbana)");
        }
        return repositorio.agregar(filtro, agrupacion, celdaGrados, celdaRuralGrados);
    }

    public PaginaHechos hechos(ServicioCliente s, Filtro filtro, int desde, int limite) {
        OperacionNegocio.LISTAR_HECHOS.autorizar(s);
        validarPeriodo(filtro);
        return repositorio.hechos(filtro, Math.max(0, desde), Math.clamp(limite, 1, 10_000));
    }

    /**
     * R14: la vista es una proyección de eventos; si se perdieron eventos (p. ej. mensajes
     * descartados de una cola de fallidos) o la proyección tuvo un error, se recalcula desde
     * los datos de origen. Sin {@code aplicar} solo informa las diferencias.
     */
    public co.edu.javeriana.servired.persistencia.dominio.Indicadores.Reconstruccion reconstruir(
            ServicioCliente s, int umbralValidacion, boolean aplicar) {
        OperacionNegocio.RECONSTRUIR_VISTA.autorizar(s);
        if (umbralValidacion < 1 || umbralValidacion > 100) {
            throw new SolicitudRechazada("Umbral de validación fuera de rango");
        }
        return transaccion.ejecutar(() -> repositorio.reconstruir(umbralValidacion, aplicar));
    }

    public EstadoVista estado(ServicioCliente s) {
        OperacionNegocio.CONSULTAR_ESTADO_VISTA.autorizar(s);
        return repositorio.estado();
    }

    private static void validarPeriodo(Filtro f) {
        if (f.desde() == null || f.hasta() == null || !f.hasta().isAfter(f.desde())
                || Duration.between(f.desde(), f.hasta()).compareTo(PERIODO_MAXIMO) > 0) {
            throw new SolicitudRechazada("Periodo inválido (máximo 400 días)");
        }
    }
}
