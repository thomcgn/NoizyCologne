package de.noizycologne.backend.json;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonValuesTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    record Payload(String title) { }

    @Test
    void readsRequiredObjectsAndKeepsEmptyArrays() {
        assertThat(JsonValues.readRequired(mapper, "{\"title\":\"Party\"}", Payload.class).title())
                .isEqualTo("Party");
        assertThat(JsonValues.readRequired(mapper, "[]", Payload[].class)).isEmpty();
    }

    @Test
    void rejectsJsonNullBeforeItCanReachApplicationCode() {
        requireNonNull(assertThatThrownBy(() -> JsonValues.readRequired(mapper, "null", Payload.class))
                .isInstanceOf(IllegalArgumentException.class))
                .hasMessageContaining("JSON-Daten dürfen nicht null sein");
    }

    @Test
    void serializesNullAsJsonText() {
        assertThat(JsonValues.write(mapper, null)).isEqualTo("null");
        assertThat(JsonValues.write(mapper, new Payload("Party"))).contains("Party");
    }
}
