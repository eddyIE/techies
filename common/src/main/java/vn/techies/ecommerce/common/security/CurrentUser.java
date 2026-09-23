package vn.techies.ecommerce.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects the caller as a {@link UserPrincipal}, resolved from the X-User-Id header the
 * gateway sets. A controller parameter annotated with this is always non-null: if the header
 * is absent the request is rejected before the handler runs.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
