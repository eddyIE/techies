package vn.techies.ecommerce.common.error;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Import;

/**
 * Registers the shared error handler in every servlet service without each one having to
 * widen its component scan to cover the common package.
 *
 * <p>Gated on a servlet web application so the reactive api-gateway never loads it.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class ErrorHandlingAutoConfiguration {
}
