package de.noizycologne.backend.api;

import static java.util.Objects.requireNonNull;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.noizycologne.backend.media.FlyerSizeLimit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTests {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detachAppender() {
        appender.stop();
        logger.detachAppender(appender);
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        logger.addAppender(appender);
        appender.start();
        return appender;
    }

    @Test
    void unexpectedExceptionStillReturnsInternalErrorAndIsLoggedWithStackTrace() {
        var handler = new ApiExceptionHandler();
        var logs = captureLogs();
        var failure = new IllegalStateException("unexpected fixture failure");

        var response = requireNonNull(handler.handleUnexpectedFailure(failure));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        var body = requireNonNull(response.getBody());
        assertThat(body.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).contains("Bitte versuche es erneut");
        assertThat(body.retryable()).isTrue();

        assertThat(logs.list).hasSize(1);
        var event = requireNonNull(logs.list.get(0));
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getFormattedMessage()).isEqualTo("Unhandled exception while processing API request");
        var thrown = requireNonNull(event.getThrowableProxy());
        assertThat(thrown.getClassName()).isEqualTo(failure.getClass().getName());
        assertThat(thrown.getStackTraceElementProxyArray()).isNotEmpty();
    }

    @Test
    void oversizedUploadReturns413WithTheSharedTenMegabyteMessage() {
        var tooLarge = new MaxUploadSizeExceededException(FlyerSizeLimit.MAX_BYTES + 1);
        assertThat(tooLarge.getMaxUploadSize()).isEqualTo(FlyerSizeLimit.MAX_BYTES + 1);

        var response = requireNonNull(new ApiExceptionHandler().handleUploadTooLarge());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        var body = requireNonNull(response.getBody());
        assertThat(body.code()).isEqualTo("FILE_TOO_LARGE");
        String message = requireNonNull(body.message());
        assertThat(message).isEqualTo(FlyerSizeLimit.tooLargeMessage());
        assertThat(message).contains("10 MB");
        assertThat(body.retryable()).isFalse();
    }
}
