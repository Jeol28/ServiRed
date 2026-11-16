package co.edu.javeriana.servired.persistencia.aplicacion;

import co.edu.javeriana.servired.persistencia.aplicacion.puertos.AlmacenEvidencias;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.Outbox;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RegistroIdempotencia;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioFase4;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.UnidadDeTrabajo;
import co.edu.javeriana.servired.persistencia.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.persistencia.dominio.Conflicto;
import co.edu.javeriana.servired.persistencia.dominio.EventoSaliente;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.Archivo;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.ArchivoNuevo;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaGuardada;
import co.edu.javeriana.servired.persistencia.dominio.Fase4.DenunciaNueva;
import co.edu.javeriana.servired.persistencia.dominio.IdCorrelacion;
import co.edu.javeriana.servired.persistencia.dominio.OperacionNegocio;
import co.edu.javeriana.servired.persistencia.dominio.RecursoNoEncontrado;
import co.edu.javeriana.servired.persistencia.dominio.ReporteGuardado;
import co.edu.javeriana.servired.persistencia.dominio.ServicioCliente;
import co.edu.javeriana.servired.persistencia.dominio.SolicitudRechazada;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gestor de transacciones con Storage: guarda el archivo y su referencia juntos.
 * El archivo se sube dentro de la transacción; si la escritura en la base de
 * datos falla, el objeto se borra (compensación).
 */
public class GestionEvidencias {

    public static final int MAXIMO_POR_REPORTE = 3;

    private final UnidadDeTrabajo transaccion;
    private final RegistroIdempotencia idempotencia;
    private final RepositorioReportes reportes;
    private final RepositorioFase4 repositorio;
    private final CatalogoTerritorial catalogo;
    private final AlmacenEvidencias almacen;
    private final Outbox outbox;
    private final Clock reloj;

    public GestionEvidencias(UnidadDeTrabajo transaccion, RegistroIdempotencia idempotencia,
                             RepositorioReportes reportes, RepositorioFase4 repositorio, CatalogoTerritorial catalogo,
                             AlmacenEvidencias almacen, Outbox outbox, Clock reloj) {
        this.transaccion = transaccion;
        this.idempotencia = idempotencia;
        this.reportes = reportes;
        this.repositorio = repositorio;
        this.catalogo = catalogo;
        this.almacen = almacen;
        this.outbox = outbox;
        this.reloj = reloj;
    }

    public ReporteGuardado guardarDeReporte(ServicioCliente servicio, IdCorrelacion correlacion, UUID idReporte,
                                            ArchivoNuevo archivo) {
        var operacion = OperacionNegocio.GUARDAR_EVIDENCIA;
        operacion.autorizar(servicio);
        return transaccion.ejecutar(() -> {
            if (idempotencia.reclamar(correlacion, operacion, servicio).isPresent()) {
                return cargar(idReporte);
            }
            if (!repositorio.bloquearReporte(idReporte)) {
                throw new RecursoNoEncontrado("Reporte inexistente");
            }
            if (repositorio.contarEvidencias(idReporte) >= MAXIMO_POR_REPORTE) {
                throw new Conflicto("LIMITE", "El reporte ya tiene " + MAXIMO_POR_REPORTE + " evidencias");
            }
            String clave = "reportes/" + idReporte + "/" + archivo.idEvidencia() + extension(archivo);
            subirYRegistrar(clave, archivo, () -> repositorio.insertarEvidencia(archivo.idEvidencia(), idReporte,
                    null, clave, archivo.tipoContenido(), archivo.contenido().length, sha256(archivo.contenido()),
                    reloj.instant()));
            idempotencia.registrarResultado(correlacion, operacion, idReporte);
            return cargar(idReporte);
        });
    }

    public Optional<Archivo> obtenerDeReporte(ServicioCliente servicio, UUID idReporte, UUID idEvidencia) {
        OperacionNegocio.OBTENER_EVIDENCIA.autorizar(servicio);
        return repositorio.evidenciaDeReporte(idReporte, idEvidencia)
                .flatMap(ref -> almacen.leer(ref.clave()).map(bytes -> new Archivo(ref.tipoContenido(), bytes)));
    }

    /** Denuncia anónima con su fotografía opcional, en una sola transacción con denuncia.recibida. */
    public DenunciaGuardada guardarDenuncia(ServicioCliente servicio, IdCorrelacion correlacion, DenunciaNueva d,
                                            ArchivoNuevo foto, EventoSaliente evento) {
        var operacion = OperacionNegocio.GUARDAR_DENUNCIA;
        operacion.autorizar(servicio);
        evento.validarPara(servicio, correlacion, Set.of("denuncia.recibida"));
        return transaccion.ejecutar(() -> {
            var previo = idempotencia.reclamar(correlacion, operacion, servicio);
            if (previo.isPresent()) {
                return repositorio.buscarDenuncia(previo.get()).orElseThrow();
            }
            if (!catalogo.existeMunicipio(d.codigoMunicipio())) {
                throw new SolicitudRechazada("Municipio inexistente: " + d.codigoMunicipio());
            }
            repositorio.insertarDenuncia(d);
            if (foto != null) {
                String clave = "denuncias/" + d.idDenuncia() + "/" + foto.idEvidencia() + extension(foto);
                subirYRegistrar(clave, foto, () -> repositorio.insertarEvidencia(foto.idEvidencia(), null,
                        d.idDenuncia(), clave, foto.tipoContenido(), foto.contenido().length, sha256(foto.contenido()),
                        reloj.instant()));
            }
            outbox.agregar(evento);
            idempotencia.registrarResultado(correlacion, operacion, d.idDenuncia());
            return repositorio.buscarDenuncia(d.idDenuncia()).orElseThrow();
        });
    }

    private void subirYRegistrar(String clave, ArchivoNuevo archivo, Runnable registrar) {
        almacen.guardar(clave, archivo.tipoContenido(), archivo.contenido());
        try {
            registrar.run();
        } catch (RuntimeException e) {
            almacen.borrar(clave);
            throw e;
        }
    }

    private ReporteGuardado cargar(UUID id) {
        return reportes.buscar(id).orElseThrow(() -> new RecursoNoEncontrado("Reporte inexistente"));
    }

    private static String extension(ArchivoNuevo a) {
        return "image/png".equals(a.tipoContenido()) ? ".png" : ".jpg";
    }

    static String sha256(byte[] contenido) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenido));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
