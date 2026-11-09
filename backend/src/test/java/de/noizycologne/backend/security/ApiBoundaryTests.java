package de.noizycologne.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.jspecify.annotations.NonNull;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static java.util.Objects.requireNonNull;

class ApiBoundaryTests {
    static final String KEY="isolated-api-boundary-fixture-key-not-for-production";
    static final String ORIGIN="https://tsa.noizycologne.de";
    private @NonNull MockHttpServletRequest trusted(String method) {
        var request=new MockHttpServletRequest(method,"/api/v1/venues");
        request.addHeader(ApiBoundaryFilter.HEADER,KEY);request.addHeader("Origin",ORIGIN);
        request.addHeader("X-Noizy-Proxy-Host","tsa.noizycologne.de");request.addHeader("X-Noizy-Proxy-Proto","https");
        request.addHeader("Sec-Fetch-Site","same-origin");return request;
    }
    private int status(ApiBoundaryFilter filter,@NonNull MockHttpServletRequest request) throws Exception {
        var response=new MockHttpServletResponse();var called=new AtomicBoolean();
        filter.doFilter(request,response,(incoming,outgoing)->called.set(true));
        if(called.get())assertThat(response.getStatus()).isEqualTo(200);
        else assertThat(response.getStatus()).isIn(403,415);
        return response.getStatus();
    }
    @Test void directApiWithoutValidTrustAndMissingConfigurationFailClosed() throws Exception {
        var filter=new ApiBoundaryFilter(KEY,"",ORIGIN);
        for(String method:new String[]{"GET","POST","PUT","PATCH","DELETE","OPTIONS"}) {
            var request=new MockHttpServletRequest(method,"/api/v1/venues");
            assertThat(status(filter,request)).isEqualTo(403);
            request.addHeader(ApiBoundaryFilter.HEADER,"forged-client-key");
            assertThat(status(filter,request)).isEqualTo(403);
        }
        assertThat(status(new ApiBoundaryFilter("","",ORIGIN),trusted("GET"))).isEqualTo(403);
    }
    @Test void mutationsRequireExactOriginAndFetchMetadataRegardlessOfProxyForgery() throws Exception {
        var filter=new ApiBoundaryFilter(KEY,"",ORIGIN);
        for(String method:new String[]{"POST","PUT","PATCH","DELETE"}) {
            assertThat(status(filter,trusted(method))).isEqualTo(200);
            for(String origin:new String[]{"https://evil.example","https://other.noizycologne.de","null","https://tsa.noizycologne.de/"}) {
                var request=trusted(method);request.removeHeader("Origin");request.addHeader("Origin",requireNonNull(origin));
                assertThat(status(filter,request)).isEqualTo(403);
            }
            var missing=trusted(method);missing.removeHeader("Origin");assertThat(status(filter,missing)).isEqualTo(403);
            for(String site:new String[]{"cross-site","same-site","none"}) {
                var request=trusted(method);request.removeHeader("Sec-Fetch-Site");request.addHeader("Sec-Fetch-Site",requireNonNull(site));
                assertThat(status(filter,request)).isEqualTo(403);
            }
            var forged=trusted(method);forged.removeHeader("X-Noizy-Proxy-Host");forged.addHeader("X-Noizy-Proxy-Host","evil.example");
            assertThat(status(filter,forged)).isEqualTo(403);
        }
    }
    @Test void multipartJsonAndBodylessMutationsWorkButFormPostsDoNot() throws Exception {
        var filter=new ApiBoundaryFilter(KEY,"",ORIGIN);
        for(String type:new String[]{"application/json","application/json;charset=UTF-8","multipart/form-data; boundary=fixture"}) {
            var request=trusted("POST");request.setContentType(type);assertThat(status(filter,request)).isEqualTo(200);
        }
        for(String type:new String[]{"text/plain","application/x-www-form-urlencoded","text/html"}) {
            var request=trusted("POST");request.setContentType(type);assertThat(status(filter,request)).isEqualTo(415);
        }
        var body=trusted("POST");body.setContent(new byte[]{1});assertThat(status(filter,body)).isEqualTo(415);
    }
    @Test void onlyPrivateHealthAndSeparatelyProtectedResetBypassTheProxyToken() throws Exception {
        var filter=new ApiBoundaryFilter(KEY,"",ORIGIN);
        assertThat(status(filter,new MockHttpServletRequest("GET","/actuator/health/readiness"))).isEqualTo(200);
        assertThat(status(filter,new MockHttpServletRequest("POST","/actuator/health/readiness"))).isEqualTo(403);
        assertThat(status(filter,new MockHttpServletRequest("POST","/internal/maintenance/factory-reset"))).isEqualTo(200);
        assertThat(status(filter,new MockHttpServletRequest("GET","/api%2fv1/venues"))).isEqualTo(403);
        assertThat(status(filter,new MockHttpServletRequest("GET","/unknown"))).isEqualTo(403);
    }
    @Test void controlledRotationAcceptsPreviousKeyAndRejectsItWhenRemoved() throws Exception {
        var during=new ApiBoundaryFilter(KEY+"new",KEY,ORIGIN);
        assertThat(status(during,trusted("GET"))).isEqualTo(200);
        assertThat(status(new ApiBoundaryFilter(KEY+"new","",ORIGIN),trusted("GET"))).isEqualTo(403);
        assertThatThrownBy(()->new ApiBoundaryFilter(KEY,"","http://tsa.noizycologne.de")).isInstanceOf(IllegalArgumentException.class);
    }
}
