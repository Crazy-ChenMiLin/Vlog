package com.tongji.agent.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal read-only API for the Agent runtime.
 *
 * <p>Guarded by {@link com.tongji.auth.config.AgentInternalTokenAuthenticationFilter}
 * using {@code X-Agent-Internal-Token}. Not exposed to the browser: per v5 §16.1
 * Java is the only trusted caller of the Pi side, and this endpoint is what the
 * Pi {@code get_post} tool calls back into.</p>
 */
@RestController
@RequestMapping("/api/internal/agent")
@RequiredArgsConstructor
public class AgentInternalPostController {

    private final AgentPostLookupService agentPostLookupService;

    @GetMapping("/posts/{id}")
    public ResponseEntity<AgentPostView> getPost(@PathVariable("id") long id) {
        return ResponseEntity.ok(agentPostLookupService.getForAgent(id));
    }
}
