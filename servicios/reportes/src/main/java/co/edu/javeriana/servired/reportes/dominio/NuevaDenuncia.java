package co.edu.javeriana.servired.reportes.dominio;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Denuncia anónima de una posible conexión fraudulenta (RF-5). Por diseño no
 * tiene ningún campo que identifique al denunciante (RNF-SEG4): ni cuenta, ni
 * contacto, ni dispositivo. Es evidencia inicial que Atención debe validar.
 */
public record NuevaDenuncia(UUID idDenuncia, TipoServicio tipoServicio, Ubicacion ubicacion, String descripcion,
                            Instant fechaRecepcion) {

    public static final int DESCRIPCION_MINIMA = 10;
    public static final int DESCRIPCION_MAXIMA = 2000;

    public static NuevaDenuncia recibir(TipoServicio tipoServicio, Ubicacion ubicacion, String descripcion,
                                        Instant ahora) {
        List<ErrorDeCampo> errores = new ArrayList<>();
        if (tipoServicio == null) {
            errores.add(new ErrorDeCampo("tipoServicio", "es obligatorio"));
        }
        if (ubicacion == null) {
            errores.add(new ErrorDeCampo("ubicacion", "es obligatoria"));
        } else if (!ubicacion.estaEnColombia()) {
            errores.add(new ErrorDeCampo("ubicacion", "debe estar dentro de Colombia"));
        }
        String texto = descripcion == null ? "" : descripcion.strip();
        if (texto.length() < DESCRIPCION_MINIMA || texto.length() > DESCRIPCION_MAXIMA) {
            errores.add(new ErrorDeCampo("descripcion", "entre 10 y 2000 caracteres"));
        }
        if (!errores.isEmpty()) {
            throw new ReporteInvalido(errores);
        }
        return new NuevaDenuncia(UUID.randomUUID(), tipoServicio, ubicacion, texto, ahora);
    }
}
