package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;

/** Bandeja de salida transaccional de eventos (ADR-06). */
public interface Outbox {

    void agregar(EventoSaliente evento);
}
