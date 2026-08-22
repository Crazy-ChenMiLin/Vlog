package com.tongji.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates the CI-only Benchmark request header without exposing the
 * endpoint. Requests without this header continue to use the normal JWT path.
 */
public class BenchmarkTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String BENCHMARK_PATH_PREFIX = "/api/internal/rag-benchmark";
    static final String HEADER_NAME = "X-Benchmark-Token";

    private final String expectedToken;

    public BenchmarkTokenAuthenticationFilter(@Value("${BENCHMARK_TOKEN:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(BENCHMARK_PATH_PREFIX);
    }

    /**
     * The benchmark controller returns {@code Mono}, so Spring MVC performs a
     * second async dispatch before writing the response. Re-authenticate that
     * dispatch from the same request header; otherwise the SecurityContext
     * cleared after the initial dispatch would turn the response into a 401.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String suppliedToken = request.getHeader(HEADER_NAME);
        if (suppliedToken == null || suppliedToken.isBlank()) {
            // Preserve manual JWT access to this internal endpoint.
            filterChain.doFilter(request, response);
            return;
        }

        if (expectedToken.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Benchmark credential is not configured");
            return;
        }
        if (!MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        )) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Benchmark credential");
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "benchmark-ci",
                null,
                AuthorityUtils.createAuthorityList("ROLE_BENCHMARK")
        );
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
