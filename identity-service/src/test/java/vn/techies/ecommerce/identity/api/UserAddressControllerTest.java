package vn.techies.ecommerce.identity.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.identity.AbstractPostgresTest;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "techies.jwt.secret=test-secret-that-is-definitely-long-enough-32"
})
@AutoConfigureMockMvc
class UserAddressControllerTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    /** Registers a user and returns their id, standing in for what the gateway would inject. */
    private UUID newUser() throws Exception {
        String email = "u-" + UUID.randomUUID() + "@example.com";
        String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1","fullName":"Test User","phone":"0901234567"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("userId").asText());
    }

    private String addressBody(boolean isDefault) {
        return """
                {"recipientName":"Nguyen Van B","phone":"0907654321","line1":"12 Nguyen Hue",
                 "ward":"Ben Nghe","district":"Quan 1","province":"Ho Chi Minh","isDefault":%s}
                """.formatted(isDefault);
    }

    private UUID createAddress(UUID userId, boolean isDefault) throws Exception {
        String body = mvc.perform(post("/addresses")
                        .header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON).content(addressBody(isDefault)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    @Test
    @DisplayName("a request without the gateway identity header is unauthenticated")
    void missingIdentityHeaderIs401() throws Exception {
        mvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("a malformed identity header is rejected rather than trusted")
    void malformedIdentityHeaderIs401() throws Exception {
        mvc.perform(get("/users/me").header(UserPrincipal.HEADER_USER_ID, "not-a-uuid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("GET /users/me returns the caller, and never their password hash")
    void getMe() throws Exception {
        UUID userId = newUser();

        mvc.perform(get("/users/me").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.fullName").value("Test User"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("GET /users/{id} is allowed for yourself and forbidden for anyone else")
    void getByIdIsScopedToSelf() throws Exception {
        UUID me = newUser();
        UUID someoneElse = newUser();

        mvc.perform(get("/users/" + me).header(UserPrincipal.HEADER_USER_ID, me))
                .andExpect(status().isOk());

        mvc.perform(get("/users/" + someoneElse).header(UserPrincipal.HEADER_USER_ID, me))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("profile update changes name and phone but not email")
    void updateProfile() throws Exception {
        UUID userId = newUser();

        mvc.perform(put("/users/me").header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Updated Name","phone":"0911111111"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Updated Name"))
                .andExpect(jsonPath("$.phone").value("0911111111"));
    }

    @Test
    @DisplayName("changing password requires the correct current password")
    void changePassword() throws Exception {
        UUID userId = newUser();

        mvc.perform(put("/users/me/password").header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"wrongpassword1","newPassword":"newpassword9"}"""))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/users/me/password").header(UserPrincipal.HEADER_USER_ID, userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"password1","newPassword":"newpassword9"}"""))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the first address becomes default automatically, so checkout always has one")
    void firstAddressBecomesDefault() throws Exception {
        UUID userId = newUser();
        createAddress(userId, false);

        mvc.perform(get("/addresses").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    @DisplayName("creating a new default clears the previous one instead of violating the index")
    void newDefaultClearsPrevious() throws Exception {
        UUID userId = newUser();
        createAddress(userId, true);
        createAddress(userId, true);

        mvc.perform(get("/addresses").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].isDefault").value(true))
                .andExpect(jsonPath("$[1].isDefault").value(false));
    }

    @Test
    @DisplayName("deleting the default promotes another address")
    void deletingDefaultPromotesAnother() throws Exception {
        UUID userId = newUser();
        createAddress(userId, true);
        UUID second = createAddress(userId, true);

        mvc.perform(delete("/addresses/" + second).header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/addresses").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].isDefault").value(true));
    }

    @Test
    @DisplayName("one user cannot read, edit or delete another user's address")
    void addressesAreScopedToOwner() throws Exception {
        UUID owner = newUser();
        UUID intruder = newUser();
        UUID addressId = createAddress(owner, true);

        mvc.perform(put("/addresses/" + addressId).header(UserPrincipal.HEADER_USER_ID, intruder)
                        .contentType(MediaType.APPLICATION_JSON).content(addressBody(false)))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/addresses/" + addressId).header(UserPrincipal.HEADER_USER_ID, intruder))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the internal endpoint enforces ownership so checkout cannot snapshot a stranger's address")
    void internalEndpointEnforcesOwnership() throws Exception {
        UUID owner = newUser();
        UUID other = newUser();
        UUID addressId = createAddress(owner, true);

        mvc.perform(get("/internal/addresses/" + addressId).param("userId", owner.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.province").value("Ho Chi Minh"));

        mvc.perform(get("/internal/addresses/" + addressId).param("userId", other.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ADDRESS_NOT_FOUND"));
    }
}
