package vn.techies.ecommerce.common.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import vn.techies.ecommerce.common.error.ApiException;
import vn.techies.ecommerce.common.error.ErrorCode;

import java.util.UUID;

/**
 * Reconstructs the caller from the headers the gateway injects. Services never parse a JWT.
 *
 * <p>This trusts X-User-Id, which is only safe because no service port is published to the
 * host and the gateway strips any inbound X-User-* header. See docs/SPEC-gateway.md.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && UserPrincipal.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        String rawId = request == null ? null : request.getHeader(UserPrincipal.HEADER_USER_ID);

        if (rawId == null || rawId.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        try {
            return new UserPrincipal(UUID.fromString(rawId),
                    request.getHeader(UserPrincipal.HEADER_USER_EMAIL));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_TOKEN, "Malformed user identity");
        }
    }
}
