package co.edu.javeriana.servired.reportes.aplicacion;

import co.edu.javeriana.servired.reportes.aplicacion.Vistas.Archivo;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.DenunciaRecibida;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ImagenSaneada;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.PaginaReportes;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.ReporteCercano;
import co.edu.javeriana.servired.reportes.aplicacion.Vistas.Voto;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.CatalogoTerritorial;
import co.edu.javeriana.servired.reportes.aplicacion.puertos.RepositorioReportes;
import co.edu.javeriana.servired.reportes.dominio.EstadoReporte;
import co.edu.javeriana.servired.reportes.dominio.IdCorrelacion;
import co.edu.javeriana.servired.reportes.dominio.Municipio;
import co.edu.javeriana.servired.reportes.dominio.NuevaDenuncia;
import co.edu.javeriana.servired.reportes.dominio.NuevoReporte;
import co.edu.javeriana.servired.reportes.dominio.ObjetoValidacion;
import co.edu.javeriana.servired.reportes.dominio.ReglaIncumplida;
import co.edu.javeriana.servired.reportes.dominio.Reporte;
import co.edu.javeriana.servired.reportes.dominio.Ubicacion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Doble de prueba de Persistencia con la misma semántica (idempotencia, versión optimista, un voto). */
public class PersistenciaEnMemoria implements RepositorioReportes, CatalogoTerritorial {

    public final Map<UUID, Reporte> reportes = new HashMap<>();
    private final Map<String, UUID> porCorrelacion = new HashMap<>();
    private final Set<String> votos = new HashSet<>();
    private final Map<UUID, byte[]> archivos = new HashMap<>();
    public final List<SobreEvento> eventos = new ArrayList<>();
    public final List<NuevaDenuncia> denuncias = new ArrayList<>();
    public SobreEvento ultimoEvento;
    public boolean disponible = true;
    /** Simula votos simultáneos: cuántas veces responder conflicto de versión. */
    public int conflictosDeVersion;

    @Override
    public Municipio resolverMunicipio(Ubicacion ubicacion, IdCorrelacion correlacion) {
        return new Municipio("11001", "BOGOTÁ, D.C.");
    }

    @Override
    public Reporte guardar(NuevoReporte r, String codigoMunicipio, SobreEvento evento, IdCorrelacion correlacion) {
        if (!disponible) {
            throw new IllegalStateException("persistencia caída");
        }
        UUID previo = porCorrelacion.get(correlacion.valor());
        if (previo != null) {
            return reportes.get(previo);
        }
        var guardado = new Reporte(r.idReporte(), r.idCiudadano(), r.tipoServicio(), r.ubicacion(),
                new Municipio(codigoMunicipio, "BOGOTÁ, D.C."), r.inicioEstimado(), r.magnitud(), r.descripcion(),
                EstadoReporte.RECIBIDO, r.fechaRegistro(), null, null, 0, null, null,
                List.of(new Reporte.EntradaHistoria(EstadoReporte.RECIBIDO, r.fechaRegistro(), "Reporte recibido")),
                List.of());
        reportes.put(r.idReporte(), guardado);
        porCorrelacion.put(correlacion.valor(), r.idReporte());
        registrar(evento);
        return guardado;
    }

    @Override
    public Optional<Reporte> buscar(UUID idReporte, IdCorrelacion correlacion) {
        return Optional.ofNullable(reportes.get(idReporte));
    }

    @Override
    public PaginaReportes listarDeCiudadano(UUID idCiudadano, int pagina, int tamano, IdCorrelacion correlacion) {
        var propios = reportes.values().stream().filter(r -> r.perteneceA(idCiudadano)).toList();
        return new PaginaReportes(propios, propios.size(), pagina, tamano);
    }

    @Override
    public List<ReporteCercano> cercanos(Ubicacion c, int radio, UUID excluir, IdCorrelacion correlacion) {
        return reportes.values().stream()
                .filter(r -> !r.perteneceA(excluir) && r.ubicacion().distanciaMetros(c) <= radio)
                .map(r -> new ReporteCercano(r.idReporte(), r.tipoServicio(), r.magnitud(), r.estado(),
                        r.inicioEstimado(), r.ubicacion(), (int) r.ubicacion().distanciaMetros(c), r.validacionCorte(),
                        r.validacionRestablecimiento()))
                .toList();
    }

    @Override
    public Reporte registrarRestablecimiento(UUID id, Reporte.Restablecimiento rs, SobreEvento evento,
                                             IdCorrelacion correlacion) {
        Reporte r = reportes.get(id);
        if (r.estado() == EstadoReporte.RESTABLECIDO || r.estado() == EstadoReporte.CERRADO) {
            throw new ReglaIncumplida("YA_RESTABLECIDO", "ya");
        }
        var historia = new ArrayList<>(r.historia());
        historia.add(new Reporte.EntradaHistoria(EstadoReporte.RESTABLECIDO, rs.fecha(), "Servicio restablecido"));
        var nuevo = new Reporte(r.idReporte(), r.idCiudadano(), r.tipoServicio(), r.ubicacion(), r.municipio(),
                r.inicioEstimado(), r.magnitud(), r.descripcion(), EstadoReporte.RESTABLECIDO, r.fechaRegistro(),
                rs.fecha(), rs.duracionMinutos(), r.versionValidaciones(), r.validacionCorte(),
                r.validacionRestablecimiento(), historia, r.evidencias());
        reportes.put(id, nuevo);
        registrar(evento);
        return nuevo;
    }

    @Override
    public Reporte registrarValidacion(UUID id, Voto v, SobreEvento evento, IdCorrelacion correlacion) {
        Reporte r = reportes.get(id);
        if (conflictosDeVersion > 0) {
            conflictosDeVersion--;
            // Otro ciudadano votó en medio: se simula su voto a favor.
            reportes.put(id, conVoto(r, v.objeto(), true));
            throw new ConflictoDeVersion();
        }
        if (v.versionEsperada() != r.versionValidaciones()) {
            throw new ConflictoDeVersion();
        }
        if (!votos.add(id + "|" + v.objeto() + "|" + v.idCiudadano())) {
            throw new VotoRepetido();
        }
        var nuevo = conVoto(r, v.objeto(), v.sentido() == co.edu.javeriana.servired.reportes.dominio.SentidoValidacion.CONFIRMA);
        reportes.put(id, nuevo);
        registrar(evento);
        return nuevo;
    }

    private static Reporte conVoto(Reporte r, ObjetoValidacion objeto, boolean confirma) {
        var sentido = confirma ? co.edu.javeriana.servired.reportes.dominio.SentidoValidacion.CONFIRMA
                : co.edu.javeriana.servired.reportes.dominio.SentidoValidacion.CONTROVIERTE;
        var corte = objeto == ObjetoValidacion.CORTE ? r.validacionCorte().con(sentido) : r.validacionCorte();
        var rest = objeto == ObjetoValidacion.RESTABLECIMIENTO
                ? r.validacionRestablecimiento().con(sentido) : r.validacionRestablecimiento();
        return new Reporte(r.idReporte(), r.idCiudadano(), r.tipoServicio(), r.ubicacion(), r.municipio(),
                r.inicioEstimado(), r.magnitud(), r.descripcion(), r.estado(), r.fechaRegistro(),
                r.fechaRestablecimiento(), r.duracionMinutos(), r.versionValidaciones() + 1, corte, rest, r.historia(),
                r.evidencias());
    }

    public final Set<UUID> eventosAplicados = new HashSet<>();

    @Override
    public synchronized Reporte aplicarEstadoDeCaso(UUID id, UUID idEvento,
                                                    co.edu.javeriana.servired.reportes.dominio.EstadoPorCaso c,
                                                    java.time.Instant fecha, IdCorrelacion correlacion) {
        Reporte r = reportes.get(id);
        if (r == null) {
            throw org.springframework.web.client.HttpClientErrorException.create(
                    org.springframework.http.HttpStatus.NOT_FOUND, "no existe", null, null, null);
        }
        if (!eventosAplicados.add(idEvento) || !c.desde().contains(r.estado())) {
            return r;
        }
        var historia = new ArrayList<>(r.historia());
        historia.add(new Reporte.EntradaHistoria(c.estadoNuevo(), fecha, c.detalle()));
        var nuevo = new Reporte(r.idReporte(), r.idCiudadano(), r.tipoServicio(), r.ubicacion(), r.municipio(),
                r.inicioEstimado(), r.magnitud(), r.descripcion(), c.estadoNuevo(), r.fechaRegistro(),
                r.fechaRestablecimiento(), r.duracionMinutos(), r.versionValidaciones(), r.validacionCorte(),
                r.validacionRestablecimiento(), historia, r.evidencias());
        reportes.put(id, nuevo);
        return nuevo;
    }

    @Override
    public Reporte guardarEvidencia(UUID id, UUID idEvidencia, ImagenSaneada imagen, IdCorrelacion correlacion) {
        Reporte r = reportes.get(id);
        archivos.put(idEvidencia, imagen.contenido());
        var evidencias = new ArrayList<>(r.evidencias());
        evidencias.add(new Reporte.Evidencia(idEvidencia, imagen.tipoContenido(), imagen.contenido().length,
                java.time.Instant.now()));
        var nuevo = new Reporte(r.idReporte(), r.idCiudadano(), r.tipoServicio(), r.ubicacion(), r.municipio(),
                r.inicioEstimado(), r.magnitud(), r.descripcion(), r.estado(), r.fechaRegistro(),
                r.fechaRestablecimiento(), r.duracionMinutos(), r.versionValidaciones(), r.validacionCorte(),
                r.validacionRestablecimiento(), r.historia(), evidencias);
        reportes.put(id, nuevo);
        return nuevo;
    }

    @Override
    public Optional<Archivo> obtenerEvidencia(UUID idReporte, UUID idEvidencia, IdCorrelacion correlacion) {
        return Optional.ofNullable(archivos.get(idEvidencia)).map(b -> new Archivo("image/png", b));
    }

    @Override
    public DenunciaRecibida guardarDenuncia(NuevaDenuncia d, String municipio, ImagenSaneada foto, SobreEvento evento,
                                            IdCorrelacion correlacion) {
        denuncias.add(d);
        registrar(evento);
        return new DenunciaRecibida(d.idDenuncia(), "PENDIENTE_VALIDACION", d.fechaRecepcion(), foto != null);
    }

    private void registrar(SobreEvento evento) {
        eventos.add(evento);
        ultimoEvento = evento;
    }
}
