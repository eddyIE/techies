package vn.techies.ecommerce.identity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.techies.ecommerce.identity.AbstractPostgresTest;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "techies.jwt.secret=test-secret-that-is-definitely-long-enough-32",
        "techies.jwt.ttl-days=30"
})
@AutoConfigureMockMvc
class AuthControllerTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    private String registerBody(String email, String password) {
        return """
                {"email":"%s","password":"%s","fullName":"Nguyen Van A","phone":"0901234567"}
                """.formatted(email, password);
    }

    private MvcResult register(String email, String password) throws Exception {
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, password)))
                .andReturn();
    }

    @Test
    @DisplayName("register then login returns a signed JWT carrying the user id and a 30-day expiry")
    void registerThenLogin() throws Exception {
        String email = uniqueEmail();

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email, "password1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.email").value(email));

        MvcResult result = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}""".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.email").value(email))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String token = body.get("accessToken").asText();

        SecretKey key = Keys.hmacShaKeyFor(
                "test-secret-that-is-definitely-long-enough-32".getBytes(StandardCharsets.UTF_8));
        var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo(body.get("user").get("id").asText());
        assertThat(claims.get("email", String.class)).isEqualTo(email);

        long ttlSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(ttlSeconds).isEqualTo(30 * 24 * 60 * 60);
        assertThat(body.get("expiresIn").asLong()).isEqualTo(ttlSeconds);
    }

    @Test
    @DisplayName("password_hash never appears in any auth response")
    void neverLeaksPasswordHash() throws Exception {
        String email = uniqueEmail();
        String registerResponse = register(email, "password1").getResponse().getContentAsString();

        MvcResult login = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}""".formatted(email)))
                .andReturn();

        assertThat(registerResponse).doesNotContain("passwordHash", "password_hash", "$2a$", "$2b$");
        assertThat(login.getResponse().getContentAsString())
                .doesNotContain("passwordHash", "password_hash", "$2a$", "$2b$");
    }

    @Test
    @DisplayName("duplicate email differing only in case is rejected as a conflict")
    void duplicateEmailRejected() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(email.toUpperCase(), "password1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("wrong password and unknown email return the same 401, leaking nothing")
    void loginFailuresAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");

        MvcResult wrongPassword = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrongpassword1"}""".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        MvcResult unknownEmail = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}""".formatted(uniqueEmail())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn();

        assertThat(json.readTree(wrongPassword.getResponse().getContentAsString()).get("message").asText())
                .isEqualTo(json.readTree(unknownEmail.getResponse().getContentAsString()).get("message").asText());
    }

    @Test
    @DisplayName("weak passwords are rejected with WEAK_PASSWORD")
    void weakPasswordRejected() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uniqueEmail(), "short1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));

        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(uniqueEmail(), "alllettersonly")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
    }

    @Test
    @DisplayName("invalid request bodies produce the shared validation envelope")
    void validationEnvelope() throws Exception {
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"password1","fullName":"A","phone":"abc"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
    }

    @Test
    @DisplayName("check-email reports existence, and reset-password then changes the password")
    void checkEmailThenReset() throws Exception {
        String email = uniqueEmail();
        register(email, "password1");

        mvc.perform(post("/auth/check-email").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true));

        mvc.perform(post("/auth/check-email").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(uniqueEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false));

        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","newPassword":"newpassword9"}""".formatted(email)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"newpassword9"}""".formatted(email)))
                .andExpect(status().isOk());

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1"}""".formatted(email)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reset-password for an unknown account is a 404")
    void resetUnknownAccount() throws Exception {
        mvc.perform(post("/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","newPassword":"newpassword9"}""".formatted(uniqueEmail())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }
}
