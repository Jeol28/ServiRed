package co.edu.javeriana.servired.identidad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.javeriana.servired.identidad.aplicacion.PersistenciaEnMemoria;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** API de identidad completa (seguridad y JWT reales) con Persistencia en memoria. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "servired.bcrypt.costo=4",
        "servired.admin-inicial.correo=admin@servired.co",
        "servired.admin-inicial.clave=clave-admin-inicial"})
class IdentidadApiTest {

    static final String EMPRESA = "a0000000-0000-4000-8000-000000000001";

    @TestConfiguration
    static class Dobles {
        @Bean
        @Primary
        PersistenciaEnMemoria persistenciaEnMemoria() {
            return new PersistenciaEnMemoria();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    PersistenciaEnMemoria persistencia;

    private ResultActions enviar(String ruta, String cuerpo) throws Exception {
        return mvc.perform(post(ruta).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private JsonNode login(String correo, String clave) throws Exception {
        String r = enviar("/api/v1/auth/login", "{\"correo\":\"%s\",\"clave\":\"%s\"}".formatted(correo, clave))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(r);
    }

    private String registrarYEntrar(String correo) throws Exception {
        enviar("/api/v1/auth/registro", """
                {"correo":"%s","clave":"clave-segura-1","nombre":"Ana","aceptaTratamientoDatos":true}"""
                .formatted(correo)).andExpect(status().isCreated());
        return "Bearer " + login(correo, "clave-segura-1").get("tokenAcceso").asText();
    }

    private String admin() throws Exception {
        // El administrador inicial se crea en segundo plano al arrancar.
        for (int i = 0; i < 50 && persistencia.cuentas.isEmpty(); i++) {
            Thread.sleep(100);
        }
        return "Bearer " + login("admin@servired.co", "clave-admin-inicial").get("tokenAcceso").asText();
    }

    @Test
    void elCiudadanoSeRegistraEntraYConsultaSuCuenta() throws Exception {
        String token = registrarYEntrar("ana1@ejemplo.co");

        mvc.perform(get("/api/v1/cuentas/yo").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").value("ana1@ejemplo.co"))
                .andExpect(jsonPath("$.rol").value("CIUDADANO"))
                .andExpect(jsonPath("$.hashClave").doesNotExist());
    }

    @Test
    void elLoginEntregaUnJwtFirmadoConElKidPublicado() throws Exception {
        registrarYEntrar("ana2@ejemplo.co");
        JsonNode sesion = login("ana2@ejemplo.co", "clave-segura-1");

        assertThat(sesion.get("tipoToken").asText()).isEqualTo("Bearer");
        assertThat(sesion.get("expiraEn").asLong()).isBetween(890L, 900L);
        String kid = SignedJWT.parse(sesion.get("tokenAcceso").asText()).getHeader().getKeyID();
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value(kid))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    @Test
    void laListaDeRevocacionSeEntregaSinTokenComoElJwks() throws Exception {
        java.util.UUID jti = java.util.UUID.randomUUID();
        persistencia.accesosRevocados.put(jti, java.time.Instant.now().plusSeconds(600));

        mvc.perform(get("/interno/v1/tokens-revocados"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generadaEn").exists())
                .andExpect(jsonPath("$.revocados[?(@.jti == '" + jti + "')]").exists());
        mvc.perform(get("/interno/v1/tokens-revocados?desde=2026-11-18T12:00:00Z")).andExpect(status().isOk());
    }

    @Test
    void sinTokenOConTokenFalsoResponde401ProblemJson() throws Exception {
        mvc.perform(get("/api/v1/cuentas/yo"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(get("/api/v1/cuentas/yo").header("Authorization", "Bearer eyJhbGciOiJub25lIn0.e30."))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unCiudadanoNoAdministraCuentas() throws Exception {
        String token = registrarYEntrar("ana3@ejemplo.co");

        mvc.perform(get("/api/v1/cuentas").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void credencialesIncorrectasDan401SinRevelarLaCuenta() throws Exception {
        registrarYEntrar("ana4@ejemplo.co");

        enviar("/api/v1/auth/login", "{\"correo\":\"ana4@ejemplo.co\",\"clave\":\"no-es-esta-12\"}")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Credenciales inválidas"));
        enviar("/api/v1/auth/login", "{\"correo\":\"nadie@ejemplo.co\",\"clave\":\"no-es-esta-12\"}")
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Credenciales inválidas"));
    }

    @Test
    void elRegistroValidaYExigeAutorizacionDeDatos() throws Exception {
        enviar("/api/v1/auth/registro", "{\"correo\":\"x\",\"clave\":\"corta\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores.length()").value(3));
        registrarYEntrar("ana5@ejemplo.co");
        enviar("/api/v1/auth/registro", """
                {"correo":"ANA5@ejemplo.co","clave":"clave-segura-1","aceptaTratamientoDatos":true}""")
                .andExpect(status().isConflict());
    }

    @Test
    void renuevaYCierraLaSesion() throws Exception {
        registrarYEntrar("ana6@ejemplo.co");
        String refresco = login("ana6@ejemplo.co", "clave-segura-1").get("tokenRefresco").asText();

        String nuevo = json.readTree(enviar("/api/v1/auth/refrescar", "{\"tokenRefresco\":\"" + refresco + "\"}")
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString()).get("tokenRefresco").asText();
        enviar("/api/v1/auth/refrescar", "{\"tokenRefresco\":\"" + refresco + "\"}").andExpect(status().isUnauthorized());

        String otro = login("ana6@ejemplo.co", "clave-segura-1").get("tokenRefresco").asText();
        enviar("/api/v1/auth/cerrar-sesion", "{\"tokenRefresco\":\"" + otro + "\"}").andExpect(status().isNoContent());
        enviar("/api/v1/auth/refrescar", "{\"tokenRefresco\":\"" + otro + "\"}").andExpect(status().isUnauthorized());
        assertThat(nuevo).isNotEqualTo(refresco);
    }

    @Test
    void elAdministradorCreaUnOperadorDeUnaEmpresa() throws Exception {
        String admin = admin();

        mvc.perform(post("/api/v1/cuentas").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"correo":"operador@empresa.co","clave":"clave-operador-1","rol":"OPERADOR",
                                 "idEmpresa":"%s"}""".formatted(EMPRESA)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rol").value("OPERADOR"))
                .andExpect(jsonPath("$.idEmpresa").value(EMPRESA));
        String tokenOperador = login("operador@empresa.co", "clave-operador-1").get("tokenAcceso").asText();
        assertThat(SignedJWT.parse(tokenOperador).getJWTClaimsSet().getStringClaim("emp")).isEqualTo(EMPRESA);

        mvc.perform(post("/api/v1/cuentas").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"op2@empresa.co\",\"clave\":\"clave-operador-1\",\"rol\":\"OPERADOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errores[0].campo").value("idEmpresa"));
    }

    @Test
    void elAdministradorNoSePuedeDesactivarASiMismo() throws Exception {
        String admin = admin();
        String id = mvc.perform(get("/api/v1/cuentas/yo").header("Authorization", admin))
                .andReturn().getResponse().getContentAsString();
        String idAdmin = json.readTree(id).get("idCuenta").asText();

        mvc.perform(patch("/api/v1/cuentas/" + idAdmin).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activa\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/cuentas/yo").header("Authorization", admin)).andExpect(status().isForbidden());
    }

    @Test
    void elCiudadanoSuprimeSuCuenta() throws Exception {
        String token = registrarYEntrar("ana7@ejemplo.co");

        mvc.perform(delete("/api/v1/cuentas/yo").header("Authorization", token)).andExpect(status().isNoContent());

        enviar("/api/v1/auth/login", "{\"correo\":\"ana7@ejemplo.co\",\"clave\":\"clave-segura-1\"}")
                .andExpect(status().isUnauthorized());
    }
}
