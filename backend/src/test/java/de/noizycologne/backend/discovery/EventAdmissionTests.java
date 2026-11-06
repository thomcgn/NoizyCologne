package de.noizycologne.backend.discovery;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EventAdmissionTests {
    private String price(String evidence) {
        return EventAdmission.price(new EventExtractor.Event("id","Party","https://source.example/event",List.of("2027-01-09"),evidence,"","Club"));
    }
    @Test void explicitEventOffersAndAdmissionLabelsTakePriorityWithoutInventingFreeAdmission() {
        assertThat(price("{\"offers\":{\"price\":12,\"priceCurrency\":\"EUR\"}}")).isEqualTo("12 €");
        assertThat(price("Eintritt: 9 €")).isEqualTo("9 €");
        assertThat(price("Eintritt: frei")).isEqualTo("Eintritt frei");
        assertThat(price("{\"offers\":{\"price\":0,\"priceCurrency\":\"EUR\"}}")).isEqualTo("Eintritt frei");
        assertThat(price("Keine Preisangabe")).isEmpty();
        assertThat(price("{\"offers\":{\"price\":12,\"priceCurrency\":\"USD\"}}")).isEmpty();
    }
}
