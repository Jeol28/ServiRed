package co.edu.javeriana.servired.persistencia.aplicacion.puertos;

import co.edu.javeriana.servired.persistencia.dominio.Atencion.CambiosCaso;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Decision;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Cobertura;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.ConsultaCobertura;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.EmpresaPrestadora;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.FiltroCasos;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.Moderacion;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.ModeracionNueva;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.PaginaCasos;
import co.edu.javeriana.servired.persistencia.dominio.Atencion.PaginaModeraciones;
import co.edu.javeriana.servired.persistencia.dominio.CasoGuardado;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Esquema atencion (y lecturas de catálogo, cuentas y denuncias que Atención necesita). */
public interface RepositorioAtencion {

    /** La zona de la consulta y su empresa dueña; vacío si no se puede ubicar. */
    Optional<Cobertura> cobertura(ConsultaCobertura consulta);

    /** Empresas prestadoras del catálogo, por nombre. */
    List<EmpresaPrestadora> empresas();

    Optional<CasoGuardado> caso(UUID idCaso);

    PaginaCasos listar(FiltroCasos filtro);

    /** @return false si la versión no coincide o el caso no existe */
    boolean actualizar(UUID idCaso, CambiosCaso cambios);

    boolean existeCaso(UUID idCaso);

    void agregarHistoria(UUID idCaso, String estado, Instant fecha, String detalle, UUID autor);

    /** ¿La cuenta es un operador activo de esa empresa? (no expone ningún otro dato). */
    boolean esOperadorDe(UUID idCuenta, UUID idEmpresa);

    void abrirModeracionSiNoExiste(ModeracionNueva m);

    Optional<Moderacion> moderacion(UUID idDenuncia);

    PaginaModeraciones listarModeraciones(String estado, UUID idEmpresa, int pagina, int tamano);

    boolean decidir(UUID idDenuncia, Decision d);

    Optional<RepositorioFase4.EvidenciaReferencia> evidenciaDeReporte(UUID idReporte, UUID idEvidencia);

    Optional<RepositorioFase4.EvidenciaReferencia> evidenciaDeDenuncia(UUID idDenuncia, UUID idEvidencia);
}
