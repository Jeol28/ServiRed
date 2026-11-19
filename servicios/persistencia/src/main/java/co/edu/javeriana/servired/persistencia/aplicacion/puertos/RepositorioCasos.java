package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import co.edu.javeriana.servired.persistencia.dominio.CasoNuevo;
import java.util.Optional;
import java.util.UUID;

public interface RepositorioCasos {

    /** Inserta el caso salvo que ya exista uno para el mismo reporte; true si lo insertó. */
    boolean insertarSiNoExiste(CasoNuevo caso);

    Optional<CasoGuardado> buscarPorReporte(UUID idReporte);
}
