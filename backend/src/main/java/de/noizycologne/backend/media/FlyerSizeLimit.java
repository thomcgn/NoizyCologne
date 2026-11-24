package de.noizycologne.backend.media;

/** Single server-side source for the maximum flyer size and its user-facing message. */
public final class FlyerSizeLimit {

    /** Matches spring.servlet.multipart.max-file-size in application.properties. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private static final String MESSAGE =
            "Die Bilddatei ist zu groß. Erlaubt sind höchstens "
                    + (MAX_BYTES / (1024L * 1024L)) + " MB.";

    private FlyerSizeLimit() { }

    public static String tooLargeMessage() {
        return MESSAGE;
    }
}
