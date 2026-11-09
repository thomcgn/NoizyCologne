package de.noizycologne.backend.maintenance;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.Objects;
import static org.mockito.Mockito.*;

class MutationGuardFilterTests {
    @Test void asynchronousErrorAndTimeoutDoNotReleaseBeforeCompletion() throws Exception {
        var gate=mock(MaintenanceGate.class); var lease=mock(MaintenanceGate.Lease.class);
        when(gate.enter()).thenReturn(lease);
        var request=mock(HttpServletRequest.class); var response=mock(HttpServletResponse.class);
        var context=mock(AsyncContext.class); var chain=mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/v1/calendar-entries");
        when(request.isAsyncStarted()).thenReturn(true); when(request.getAsyncContext()).thenReturn(context);
        new MutationGuardFilter(gate).doFilter(request,response,chain);
        var capture=ArgumentCaptor.forClass(AsyncListener.class);
        verify(context).addListener(capture.capture()); verify(lease,never()).close();
        var listener=Objects.requireNonNull(capture.getValue()); var event=new AsyncEvent(context);
        listener.onError(event); listener.onTimeout(event); verify(lease,never()).close();
        listener.onStartAsync(event); verify(context,times(2)).addListener(listener);
        listener.onComplete(event); verify(lease).close();
    }

    @Test void synchronousControllerFailureStillReleasesTheLease() throws Exception {
        var gate=mock(MaintenanceGate.class); var lease=mock(MaintenanceGate.Lease.class);
        when(gate.enter()).thenReturn(lease);
        var request=mock(HttpServletRequest.class); var response=mock(HttpServletResponse.class);
        var chain=mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/v1/parties");
        Objects.requireNonNull(doThrow(new jakarta.servlet.ServletException("fixture")).when(chain)).doFilter(request,response);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new MutationGuardFilter(gate).doFilter(request,response,chain))
            .isInstanceOf(jakarta.servlet.ServletException.class);
        verify(lease).close();
    }
}
