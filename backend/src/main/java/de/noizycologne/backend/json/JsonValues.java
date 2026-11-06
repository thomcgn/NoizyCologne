package de.noizycologne.backend.json;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

/** JSON objects required by an application contract must not deserialize to JSON null. */
public final class JsonValues {
    private JsonValues() { }

    public static @NonNull String write(ObjectMapper mapper, @Nullable Object value) {
        return Objects.requireNonNull(mapper.writeValueAsString(value), "JSON-Ausgabe darf nicht null sein");
    }

    public static <T> @NonNull T readRequired(ObjectMapper mapper, String content, Class<T> type) {
        // Jackson can return null even when the requested Java class is non-null.
        @Nullable T value = mapper.readValue(content, mapper.constructType(type));
        if (value == null) {
            throw new IllegalArgumentException("Erforderliche JSON-Daten dürfen nicht null sein");
        }
        return value;
    }
}
