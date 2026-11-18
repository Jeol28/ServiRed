package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Indicadores.ActualizacionVista;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Agregado;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Agrupacion;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.EstadoVista;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.Filtro;
import co.edu.javeriana.servired.persistencia.dominio.Indicadores.PaginaHechos;
import java.util.List;

/** Esquema indicadores (vista de lectura) más el catálogo territorial para los nombres. */
public interface RepositorioIndicadores {

    void actualizar(ActualizacionVista a);

    List<Agregado> agregar(Filtro filtro, Agrupacion agrupacion, double celdaGrados);

    PaginaHechos hechos(Filtro filtro, int desde, int limite);

    EstadoVista estado();

    /** Compara la vista con el origen y, si {@code aplicar}, la reescribe desde el origen (R14). */
    co.edu.javeriana.servired.persistencia.dominio.Indicadores.Reconstruccion reconstruir(int umbralValidacion,
                                                                                          boolean aplicar);
}
