package com.evolveum.polygon.connector.msgraphapi.authentication;

/**
 * Sanitized authentication failure. Raw provider responses and credential
 * material must not be attached to or exposed by this exception.
 */
public final class TokenAcquisitionException extends Exception {

    public enum Reason {
        INVALID_CREDENTIAL,
        INVALID_TENANT,
        INVALID_CLIENT,
        AUTHORITY_UNAVAILABLE,
        PROXY_UNAVAILABLE,
        NETWORK_UNAVAILABLE,
        TLS_FAILURE,
        UNEXPECTED
    }

    private final Reason reason;

    public TokenAcquisitionException(Reason reason, String sanitizedMessage) {
        super(sanitizedMessage);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
