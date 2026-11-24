package de.noizycologne.backend.cancellation;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CancellationControllerTests {

    private final CancellationService service = mock(CancellationService.class);
    private final CancellationController controller = new CancellationController(service);

    @Test
    void listAndHistoryDelegateToService() {
        when(service.list()).thenReturn(List.of(Map.of("id", "cancellation-1")));
        when(service.history("cancellation-1")).thenReturn(List.of(Map.of("action", "CONFIRMED")));

        assertThat(controller.list()).containsExactly(Map.of("id", "cancellation-1"));
        assertThat(controller.history("cancellation-1")).containsExactly(Map.of("action", "CONFIRMED"));

        verify(service).list();
        verify(service).history("cancellation-1");
    }

    @Test
    void confirmDelegatesAllFieldsAndReturnsCreatedId() {
        when(service.confirm("entry-1", "change-1", "OFFICIAL_SOURCE", "REPLACEMENT", "candidate-1"))
                .thenReturn("cancellation-1");

        Map<String, String> response = controller.confirm(new CancellationController.Confirm(
                "entry-1", "change-1", "OFFICIAL_SOURCE", "REPLACEMENT", "candidate-1"));

        assertThat(response).containsExactly(entry("id", "cancellation-1"));
        verify(service).confirm("entry-1", "change-1", "OFFICIAL_SOURCE", "REPLACEMENT", "candidate-1");
    }

    @Test
    void confirmPropagatesInvalidTriggerAndUnknownEvent() {
        var request = new CancellationController.Confirm("entry-1", null, "UNKNOWN", "UNSPECIFIED", null);
        when(service.confirm("entry-1", null, "UNKNOWN", "UNSPECIFIED", null))
                .thenThrow(new IllegalArgumentException("Bestätigter Absageauslöser erforderlich"));

        assertThatThrownBy(() -> controller.confirm(request))
                .hasMessageContaining("Bestätigter Absageauslöser");

        var missing = new CancellationController.Confirm("missing", null, "MANUAL", "UNSPECIFIED", null);
        when(service.confirm("missing", null, "MANUAL", "UNSPECIFIED", null))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Veranstaltung fehlt"));

        assertThatThrownBy(() -> controller.confirm(missing))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));
    }

    @Test
    void graphicEndpointsDelegateAndPropagateMissingOrFailedPreparation() throws Exception {
        when(service.prepareGraphic("cancellation-1")).thenReturn("media-1");
        var resource = mock(FileSystemResource.class);
        when(service.graphic("cancellation-1")).thenReturn(resource);

        assertThat(controller.prepare("cancellation-1")).containsExactly(entry("mediaAssetId", "media-1"));
        assertThat(controller.graphic("cancellation-1")).isSameAs(resource);

        when(service.prepareGraphic("missing"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Cancellation fehlt"));
        assertThatThrownBy(() -> controller.prepare("missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));

        when(service.prepareGraphic("broken")).thenThrow(new IOException("fixture"));
        assertThatThrownBy(() -> controller.prepare("broken")).isInstanceOf(IOException.class);
    }

    @Test
    void updateAnnouncementDelegatesAndPropagatesOptimisticConflict() {
        controller.update("cancellation-1",
                new CancellationController.Announcement("Neutraler Absagetext", "DRAFT", 0));
        verify(service).updateAnnouncement("cancellation-1", "Neutraler Absagetext", "DRAFT", 0);

        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Absageentwurf inzwischen geändert"))
                .when(service).updateAnnouncement("cancellation-1", "Neutraler Absagetext", "DRAFT", 1);
        assertThatThrownBy(() -> controller.update("cancellation-1",
                new CancellationController.Announcement("Neutraler Absagetext", "DRAFT", 1)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value()));
    }

    @Test
    void payloadValidationRejectsBlankNullOversizedAndNegativeValues() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new CancellationController.Confirm(
                "", "change-1", "MANUAL", "UNSPECIFIED", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("calendarEntryId");
        assertThat(validator.validate(new CancellationController.Confirm(
                "entry-1", "change-1", " ", "UNSPECIFIED", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("trigger");
        assertThat(validator.validate(new CancellationController.Confirm(
                "entry-1", "change-1", "MANUAL", "", null)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("reasonCode");
        assertThat(validator.validate(new CancellationController.Confirm(
                "entry-1", "change-1", "MANUAL", "UNSPECIFIED", null))).isEmpty();

        assertThat(validator.validate(new CancellationController.Announcement(null, "DRAFT", 0)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("caption");
        assertThat(validator.validate(new CancellationController.Announcement("Text", " ", 0)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("status");
        assertThat(validator.validate(new CancellationController.Announcement("Text", "DRAFT", -1)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("revision");
        assertThat(validator.validate(new CancellationController.Announcement("x".repeat(12001), "DRAFT", 0)))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("caption");
        assertThat(validator.validate(new CancellationController.Announcement("x".repeat(12000), "DRAFT", 0)))
                .isEmpty();
    }
}
