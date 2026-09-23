package vn.techies.ecommerce.common.error;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionTest {

    @Test
    @DisplayName("carries its ErrorCode, which decides the HTTP status")
    void carriesCode() {
        ApiException ex = new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS, "taken");

        assertThat(ex.code()).isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS);
        assertThat(ex.code().status()).isEqualTo(409);
        assertThat(ex.getMessage()).isEqualTo("taken");
    }

    @Test
    @DisplayName("factory helpers produce the expected codes")
    void factories() {
        assertThat(ApiException.notFound("Order").code()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(ApiException.forbidden("other user's order").code()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ApiException.conflict(ErrorCode.EMPTY_CART, "empty").code()).isEqualTo(ErrorCode.EMPTY_CART);
    }
}
