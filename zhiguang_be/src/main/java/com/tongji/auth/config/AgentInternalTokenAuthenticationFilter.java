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
 * Authenticates internal Agent runtime calls (Pithagoras get_post) with a
 * dedicated service credential.
 *
 * <p>Deliberately separate from {@link BenchmarkTokenAuthenticationFilter}:
 * the benchmark credential is a CI value, while this one guards the Agent
 * internal API and must be a strong random secret (v5 §16.1).</p>
 */
public class AgentInternalTokenAuthenticationFilter extends OncePerRequestFilter {

    static final String AGENT_PATH_PREFIX = "/api/internal/agent";
    static final String HEADER_NAME = "X-Agent-Internal-Token";

    private final String expectedToken;

    public AgentInternalTokenAuthenticationFilter(@Value("${AGENT_INTERNAL_TOKEN:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(AGENT_PATH_PREFIX);
    }

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
            // No internal credential: fall through to the normal JWT path.
            filterChain.doFilter(request, response);
            return;
        }

        if (expectedToken.isBlank()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Agent internal credential is not configured");
            return;
        }
        if (!MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8)
        )) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Agent internal credential");
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "agent-runtime",
                null,
                AuthorityUtils.createAuthorityList("ROLE_AGENT_INTERNAL")
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
