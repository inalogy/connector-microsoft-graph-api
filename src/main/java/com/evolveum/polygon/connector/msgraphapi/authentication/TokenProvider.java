package com.evolveum.polygon.connector.msgraphapi.authentication;

/**
 * Acquires access tokens for a resource scope.
 *
 * <p>The provider owns any authentication client and cache associated with a
 * connector configuration. Callers must never log returned values.</p>
 */
public interface TokenProvider extends AutoCloseable {

    String acquireToken(String scope) throws TokenAcquisitionException;

    String reacquireToken(String scope) throws TokenAcquisitionException;

    @Override
    default void close() {
        // Most providers do not own closeable resources.
    }
}
