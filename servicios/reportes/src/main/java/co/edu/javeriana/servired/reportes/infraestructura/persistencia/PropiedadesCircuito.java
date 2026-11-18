package co.edu.javeriana.servired.reportes.infraestructura.persistencia;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Circuit breaker hacia Persistencia (ADR-20). Se abre si en las últimas
 * {@code ventana} llamadas (con al menos {@code minimoLlamadas}) falla el
 * {@code porcentajeFallas} %; abierto, cada llamada falla al instante durante
 * {@code esperaAbierto} y después deja pasar {@code llamadasDePrueba}.
 */
@ConfigurationProperties(prefix = "servired.persistencia.circuito")
public record PropiedadesCircuito(int ventana, int minimoLlamadas, float porcentajeFallas, Duration esperaAbierto,
                                  int llamadasDePrueba) {

    public PropiedadesCircuito {
        ventana = ventana <= 0 ? 20 : ventana;
        minimoLlamadas = minimoLlamadas <= 0 ? 10 : minimoLlamadas;
        porcentajeFallas = porcentajeFallas <= 0 ? 50 : porcentajeFallas;
        esperaAbierto = esperaAbierto == null ? Duration.ofSeconds(15) : esperaAbierto;
        llamadasDePrueba = llamadasDePrueba <= 0 ? 3 : llamadasDePrueba;
    }

    public static PropiedadesCircuito porDefecto() {
        return new PropiedadesCircuito(0, 0, 0, null, 0);
    }
}
