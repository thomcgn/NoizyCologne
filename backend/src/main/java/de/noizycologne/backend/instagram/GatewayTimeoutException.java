package de.noizycologne.backend.instagram;

/** Timeout an der externen Grenze: Remote-Ergebnis unbekannt, kein blindes Retry. */
public final class GatewayTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public GatewayTimeoutException(String message) {
        super(message);
    }
}
