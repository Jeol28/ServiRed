package co.edu.javeriana.servired.persistencia.infraestructura.jdbc;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class UnidadDeTrabajoJdbc implements UnidadDeTrabajo {

    private final TransactionTemplate plantilla;

    public UnidadDeTrabajoJdbc(TransactionTemplate plantilla) {
        this.plantilla = plantilla;
    }

    @Override
    public <T> T ejecutar(Supplier<T> bloque) {
        return plantilla.execute(estado -> bloque.get());
    }
}
