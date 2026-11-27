package de.noizycologne.backend.instagram.gateway;

/** Fehler an der Meta-Grenze. Enthält bewusst keine Tokens und keine Response-Bodies. */
public final class InstagramGatewayException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InstagramGatewayException(String message) {
        super(message);
    }
}
