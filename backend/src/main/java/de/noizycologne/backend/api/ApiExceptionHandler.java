package de.noizycologne.backend.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        return error(status, responseStatusCode(status, exception.getReason()),
                responseStatusMessage(status, exception.getReason()), status == HttpStatus.SERVICE_UNAVAILABLE
                        || status == HttpStatus.GATEWAY_TIMEOUT);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                fields.isBlank() ? "Bitte prüfe die Eingaben." : "Bitte prüfe die Eingaben: " + fields, false);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Bitte prüfe die Eingaben.", false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableBody() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Die Anfrage enthält ungültige oder unvollständige Daten.", false);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> handleUploadTooLarge() {
        return error(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                "Die Bilddatei ist zu groß. Erlaubt sind höchstens 8 MB.", false);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiErrorResponse> handleDatabaseFailure() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "Die Datenbank ist momentan nicht erreichbar. Bitte später erneut versuchen.", true);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), false);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> handleUnexpectedFailure() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Der Server konnte die Anfrage nicht verarbeiten. Bitte versuche es erneut.", true);
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message, boolean retryable) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(code, message, status.value(), retryable));
    }

    private String responseStatusCode(HttpStatus status, @Nullable String reason) {
        if (containsAny(reason, "Tesseract", "Texterkennung")) {
            return status == HttpStatus.GATEWAY_TIMEOUT ? "OCR_TIMEOUT" : "OCR_UNAVAILABLE";
        }
        return switch (status) {
            case BAD_REQUEST -> "INVALID_REQUEST";
            case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
            case FORBIDDEN -> "ACCESS_DENIED";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case CONFLICT -> "CONFLICT";
            case CONTENT_TOO_LARGE -> "FILE_TOO_LARGE";
            case UNPROCESSABLE_CONTENT -> "INVALID_CONTENT";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            case GATEWAY_TIMEOUT -> "REQUEST_TIMEOUT";
            default -> "REQUEST_FAILED";
        };
    }

    private String responseStatusMessage(HttpStatus status, @Nullable String reason) {
        if (reason != null && !reason.isBlank()) {
            if (containsAny(reason, "lokale Flyer-Texterkennung", "Tesseract ist", "Tesseract hat")) {
                return status == HttpStatus.GATEWAY_TIMEOUT
                        ? "Die Genre-Erkennung hat zu lange gedauert. Bitte versuche es mit einem kleineren Flyer erneut."
                        : "Die Genre-Erkennung ist momentan nicht verfügbar. Prüfe, ob Tesseract lokal installiert ist, und versuche es erneut.";
            }
            return reason;
        }
        return switch (status) {
            case SERVICE_UNAVAILABLE -> "Der Dienst ist momentan nicht verfügbar. Bitte später erneut versuchen.";
            case GATEWAY_TIMEOUT -> "Die Anfrage hat zu lange gedauert. Bitte erneut versuchen.";
            case INTERNAL_SERVER_ERROR -> "Der Server konnte die Anfrage nicht verarbeiten.";
            default -> "Die Anfrage konnte nicht verarbeitet werden.";
        };
    }

    private boolean containsAny(@Nullable String value, String... candidates) {
        if (value == null) return false;
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }
}
