package com.tongji.agent.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Calls the Pithagoras webhook on behalf of a real logged-in user.
 *
 * <p>The webhook is synchronous (it awaits the whole Agent turn), so every call
 * here is bounded by {@code agent.timeout-seconds}. A Java timeout does NOT
 * mean the upstream run stopped — see v5 §19.4.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PithagorasClient {

    private final AgentProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String ask(long postId, long userId, String userDisplayName, String message) {
        String url = props.getPithagorasBaseUrl().replaceAll("/+$", "") + "/";
        Map<String, Object> body = Map.of(
                "session", "post:" + postId,
                "message", message,
                "from", Map.of("id", String.valueOf(userId), "name", userDisplayName)
        );
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("X-Portal-Secret", props.getPithagorasSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("webhook HTTP " + response.statusCode());
            }
            Map<?, ?> parsed = objectMapper.readValue(response.body(), Map.class);
            Object reply = parsed.get("reply");
            return reply == null ? "" : String.valueOf(reply);
        } catch (java.time.format.DateTimeParseException e) {
            throw e;
        } catch (Exception e) {
            log.warn("pithagoras call failed post={} user={}: {}", postId, userId, e.getMessage());
            throw new IllegalStateException("pithagoras call failed: " + e.getMessage(), e);
        }
    }
}
