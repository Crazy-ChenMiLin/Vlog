package com.tongji.auth.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkTokenAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void grantsBenchmarkAuthenticationForMatchingHeader() throws Exception {
        BenchmarkTokenAuthenticationFilter filter = new BenchmarkTokenAuthenticationFilter("expected-token");
        MockHttpServletRequest request = benchmarkRequest("expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean authenticatedInsideChain = new AtomicBoolean(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> authenticatedInsideChain.set(
                SecurityContextHolder.getContext().getAuthentication() != null
                        && "benchmark-ci".equals(SecurityContextHolder.getContext().getAuthentication().getName())
        ));

        assertThat(authenticatedInsideChain).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsIncorrectHeaderBeforeTheController() throws Exception {
        BenchmarkTokenAuthenticationFilter filter = new BenchmarkTokenAuthenticationFilter("expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(benchmarkRequest("wrong-token"), response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
    }

    @Test
    void leavesRequestsWithoutTheHeaderForNormalJwtAuthentication() throws Exception {
        BenchmarkTokenAuthenticationFilter filter = new BenchmarkTokenAuthenticationFilter("expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(benchmarkRequest(null), response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest benchmarkRequest(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/internal/rag-benchmark/single-case");
        if (token != null) {
            request.addHeader(BenchmarkTokenAuthenticationFilter.HEADER_NAME, token);
        }
        return request;
    }
}
