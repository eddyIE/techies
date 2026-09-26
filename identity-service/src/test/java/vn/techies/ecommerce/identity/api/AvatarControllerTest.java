package vn.techies.ecommerce.identity.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import vn.techies.ecommerce.common.security.UserPrincipal;
import vn.techies.ecommerce.identity.AbstractPostgresTest;

import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "techies.jwt.secret=test-secret-that-is-definitely-long-enough-32"
})
@AutoConfigureMockMvc
class AvatarControllerTest extends AbstractPostgresTest {

    private static final byte[] PNG_HEADER = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_HEADER = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private UUID userId;

    @BeforeEach
    void register() throws Exception {
        String email = "avatar-" + UUID.randomUUID() + "@techies.vn";
        String body = mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"password1","fullName":"Avatar User","phone":"0901234567"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        userId = UUID.fromString(json.readTree(body).get("userId").asText());
    }

    /** Bytes that begin with a real signature, padded to the requested length. */
    private static byte[] image(byte[] magic, int totalBytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(magic);
        out.writeBytes(new byte[Math.max(0, totalBytes - magic.length)]);
        return out.toByteArray();
    }

    private MockMultipartFile file(String name, String contentType, byte[] data) {
        return new MockMultipartFile("file", name, contentType, data);
    }

    @Test
    @DisplayName("a PNG uploads, and is then served back with the right content type")
    void uploadsPng() throws Exception {
        mvc.perform(multipart("/users/me/avatar").file(file("me.png", "image/png", image(PNG_HEADER, 5000)))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/users/" + userId + "/avatar"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")));
    }

    @Test
    @DisplayName("a JPEG uploads too")
    void uploadsJpeg() throws Exception {
        mvc.perform(multipart("/users/me/avatar").file(file("me.jpg", "image/jpeg", image(JPEG_HEADER, 4000)))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/users/" + userId + "/avatar"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    @DisplayName("uploading again replaces the previous image rather than erroring")
    void replacesExisting() throws Exception {
        mvc.perform(multipart("/users/me/avatar").file(file("a.png", "image/png", image(PNG_HEADER, 3000)))
                .header(UserPrincipal.HEADER_USER_ID, userId));

        mvc.perform(multipart("/users/me/avatar").file(file("b.jpg", "image/jpeg", image(JPEG_HEADER, 6000)))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isNoContent());

        mvc.perform(get("/users/" + userId + "/avatar"))
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    @DisplayName("SECURITY: a non-image uploaded as image/png is rejected on its bytes, not its label")
    void rejectsDisguisedFile() throws Exception {
        byte[] notAnImage = "#!/bin/sh\nrm -rf /\n".getBytes();

        mvc.perform(multipart("/users/me/avatar").file(file("evil.png", "image/png", notAnImage))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    @Test
    @DisplayName("SECURITY: a real JPEG mislabelled as image/png is refused")
    void rejectsContentTypeMismatch() throws Exception {
        mvc.perform(multipart("/users/me/avatar").file(file("x.png", "image/png", image(JPEG_HEADER, 2000)))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    @Test
    @DisplayName("GIF and other formats are refused even though they are images")
    void rejectsOtherImageFormats() throws Exception {
        byte[] gif = "GIF89a".getBytes();

        mvc.perform(multipart("/users/me/avatar").file(file("me.gif", "image/gif", gif))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE_TYPE"));
    }

    @Test
    @DisplayName("anything over 2MB is refused with 413")
    void rejectsOversizedImage() throws Exception {
        byte[] tooBig = image(PNG_HEADER, 2 * 1024 * 1024 + 1);

        mvc.perform(multipart("/users/me/avatar").file(file("big.png", "image/png", tooBig))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    @DisplayName("exactly 2MB is accepted — the limit is inclusive")
    void acceptsExactlyTwoMegabytes() throws Exception {
        mvc.perform(multipart("/users/me/avatar")
                        .file(file("edge.png", "image/png", image(PNG_HEADER, 2 * 1024 * 1024)))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("an empty file is refused")
    void rejectsEmptyFile() throws Exception {
        mvc.perform(multipart("/users/me/avatar").file(file("empty.png", "image/png", new byte[0]))
                        .header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("uploading requires authentication")
    void uploadRequiresAuth() throws Exception {
        mvc.perform(multipart("/users/me/avatar").file(file("me.png", "image/png", image(PNG_HEADER, 1000))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a user with no avatar is a 404, not an empty 200")
    void missingAvatarIsNotFound() throws Exception {
        mvc.perform(get("/users/" + userId + "/avatar"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deleting removes it; deleting again is a 404")
    void deleteAvatar() throws Exception {
        mvc.perform(multipart("/users/me/avatar").file(file("me.png", "image/png", image(PNG_HEADER, 1500)))
                .header(UserPrincipal.HEADER_USER_ID, userId));

        mvc.perform(delete("/users/me/avatar").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/users/me/avatar").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(status().isNotFound());
        mvc.perform(get("/users/" + userId + "/avatar"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("avatarUrl appears on the user only once an image exists")
    void avatarUrlReflectsState() throws Exception {
        mvc.perform(get("/users/me").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());

        mvc.perform(multipart("/users/me/avatar").file(file("me.png", "image/png", image(PNG_HEADER, 1200)))
                .header(UserPrincipal.HEADER_USER_ID, userId));

        mvc.perform(get("/users/me").header(UserPrincipal.HEADER_USER_ID, userId))
                .andExpect(jsonPath("$.avatarUrl").value("/users/" + userId + "/avatar"));
    }
}
