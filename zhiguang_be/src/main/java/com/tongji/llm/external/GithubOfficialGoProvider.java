package com.tongji.llm.external;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Link-only GitHub provider for official Go sources. It uses authenticated Code
 * Search when a server-side token is configured and falls back to a small,
 * explicit catalogue when GitHub is unavailable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubOfficialGoProvider implements ExternalKnowledgeProvider {
    private static final Set<String> GO_TERMS = Set.of(
            "golang", "go语言", "go 语言", "go的", "go中", "go里", "goroutine", "gmp", "channel",
            "defer", "panic", "recover", "go mod", "go module", "go modules",
            "gofmt", "go test", "go build", "go vet", "go generate", "go workspace", "go1."
    );
    private final RestClient.Builder restClientBuilder;
    private final GithubOfficialGoProperties properties;

    @Override
    public boolean supports(String question) {
        if (!properties.isEnabled() || !StringUtils.hasText(question)) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return GO_TERMS.stream().anyMatch(normalized::contains)
                || normalized.matches(".*\\bgo\\b.*");
    }

    @Override
    public List<ExternalKnowledgeResource> findResources(String question, int maxResults) {
        if (!supports(question)) {
            return List.of();
        }
        int limit = Math.min(properties.getMaxResults(), Math.max(1, maxResults));
        List<ExternalKnowledgeResource> searched = searchGithub(question, limit);
        return searched.isEmpty() ? catalogue(question, limit) : searched;
    }

    private List<ExternalKnowledgeResource> searchGithub(String question, int limit) {
        if (!StringUtils.hasText(properties.getToken()) || !validRepository(properties.getRepository())) {
            return List.of();
        }
        try {
            GithubCodeSearchResponse response = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .build()
                    .get()
                    .uri(uriBuilder -> uriBuilder.path("/search/code")
                            .queryParam("q", searchQuery(question))
                            .queryParam("per_page", limit)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "zhiguang-external-knowledge-mvp")
                    .headers(headers -> headers.setBearerAuth(properties.getToken()))
                    .retrieve()
                    .body(GithubCodeSearchResponse.class);
            if (response == null || response.items() == null) {
                return List.of();
            }
            return response.items().stream()
                    .filter(this::allowedResult)
                    .limit(limit)
                    .map(item -> new ExternalKnowledgeResource(
                            "GitHub 官方 Go 搜索结果",
                            item.name(),
                            item.repository().fullName(),
                            item.path(),
                            item.htmlUrl(),
                            "GitHub Code Search 命中：" + item.path()
                    ))
                    .toList();
        } catch (Exception e) {
            // Token values must never appear in logs. A failed provider must not
            // prevent the original RAG request from finishing.
            log.info("Official Go GitHub Code Search unavailable: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private String searchQuery(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        List<String> hints = new ArrayList<>();
        addHint(hints, normalized, "协程", "goroutine");
        addHint(hints, normalized, "并发", "concurrency");
        addHint(hints, normalized, "通道", "channel");
        addHint(hints, normalized, "接口", "interface");
        addHint(hints, normalized, "泛型", "generics");
        addHint(hints, normalized, "切片", "slice");
        addHint(hints, normalized, "映射", "map");
        addHint(hints, normalized, "模块", "module");
        addHint(hints, normalized, "依赖", "module");
        addHint(hints, normalized, "逃逸", "escape");
        addHint(hints, normalized, "垃圾回收", "gc");
        Stream.of(normalized.split("[^a-z0-9._-]+"))
                .filter(term -> term.length() >= 2)
                .filter(term -> !term.equals("golang") && !term.equals("go"))
                .limit(4)
                .forEach(hints::add);
        String terms = hints.stream().distinct().limit(4).collect(Collectors.joining(" "));
        if (!StringUtils.hasText(terms)) {
            terms = "goroutine";
        }
        return terms + " repo:" + properties.getRepository();
    }

    private void addHint(List<String> hints, String question, String chineseTerm, String searchTerm) {
        if (question.contains(chineseTerm)) {
            hints.add(searchTerm);
        }
    }

    private boolean allowedResult(GithubCodeSearchItem item) {
        return item != null
                && item.repository() != null
                && properties.getRepository().equals(item.repository().fullName())
                && StringUtils.hasText(item.path())
                && StringUtils.hasText(item.htmlUrl());
    }

    private boolean validRepository(String repository) {
        return repository != null && repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    }

    private List<ExternalKnowledgeResource> catalogue(String question, int limit) {
        String normalized = question.toLowerCase(Locale.ROOT);
        LinkedHashSet<ExternalKnowledgeResource> resources = new LinkedHashSet<>();
        if (containsAny(normalized, "defer", "panic", "recover")) {
            resources.add(resource("Go specification: defer / panic / recover", "doc/go_spec.html"));
            resources.add(resource("Go runtime panic implementation", "src/runtime/panic.go"));
        }
        if (containsAny(normalized, "goroutine", "channel", "并发", "协程", "gmp")) {
            resources.add(resource("Go specification: concurrency", "doc/go_spec.html"));
            resources.add(resource("Go runtime scheduler implementation", "src/runtime/proc.go"));
        }
        resources.add(resource("The Go Programming Language Specification", "doc/go_spec.html"));
        resources.add(resource("Effective Go", "doc/effective_go.html"));
        return new ArrayList<>(resources).stream().limit(limit).toList();
    }

    private ExternalKnowledgeResource resource(String title, String path) {
        String repository = "golang/go";
        return new ExternalKnowledgeResource(
                "GitHub 官方 Go 文档", title, repository, path,
                "https://github.com/" + repository + "/blob/master/" + path,
                "官方白名单资料：" + path
        );
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GithubCodeSearchResponse(List<GithubCodeSearchItem> items) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GithubCodeSearchItem(
            String name,
            String path,
            @JsonProperty("html_url") String htmlUrl,
            GithubRepository repository
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GithubRepository(@JsonProperty("full_name") String fullName) { }
}
