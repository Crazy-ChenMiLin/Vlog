package com.tongji.llm.enhanceService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RerankService {
    private final RestClient.Builder restClientBuilder;

    @Value("${rag.rerank.enabled}")
    private boolean enabled;

    @Value("${rag.rerank.base-url}")
    private String baseUrl;

    @Value("${rag.rerank.api-key:}")
    private String apiKey;

    @Value("${rag.rerank.model}")
    private String model;

    @Value("${rag.rerank.path}")
    private String path;

    public List<Document> rerank(String standaloneQuestion, List<Document> fusedDocs, int topK) {
        if (fusedDocs == null || fusedDocs.isEmpty() || topK <= 0) {
            return List.of();
        }
        if (!enabled || !StringUtils.hasText(apiKey) || !StringUtils.hasText(standaloneQuestion)) {
            return fallback(fusedDocs, topK);
        }

        //rerank模型只是发送了内容的text
        //没有title
        try {
            RerankRequest request = new RerankRequest(
                    model,
                    new TextInput(standaloneQuestion),
                    fusedDocs.stream()
                            .map(this::buildRerankText)
                            .filter(StringUtils::hasText)
                            .map(TextInput::new)
                            .toList(),
                    "END"
            );
            if (request.passages().isEmpty()) {
                return List.of();
            }

            RerankResponse response = restClientBuilder
                    .baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .body(request)
                    .retrieve()
                    .body(RerankResponse.class);

            if (response == null || response.rankings() == null || response.rankings().isEmpty()) {
                return fallback(fusedDocs, topK);
            }

            List<Document> reranked = response.rankings().stream()
                    .filter(ranking -> ranking.index() >= 0 && ranking.index() < fusedDocs.size())
                    .sorted(Comparator.comparingDouble(Ranking::logit).reversed())
                    .limit(topK)
                    .map(ranking -> fusedDocs.get(ranking.index()))
                    .toList();
            return reranked.isEmpty() ? fallback(fusedDocs, topK) : reranked;
        } catch (Exception e) {
            log.warn("Rerank failed, fallback to fused docs: {}", e.getMessage());
            return fallback(fusedDocs, topK);
        }
    }

    private List<Document> fallback(List<Document> fusedDocs, int topK) {
        return fusedDocs.stream().limit(topK).toList();
    }

    private String buildRerankText(Document document) {
        String text = document.getText() == null ? "" : document.getText();
        Object title = document.getMetadata().get("title");
        String titleText = title == null ? "" : String.valueOf(title);
        if (StringUtils.hasText(titleText)) {
            return "标题：" + titleText + "\n正文：\n" + text;
        }
        return text;
    }

    private record RerankRequest(
            String model,
            TextInput query,
            List<TextInput> passages,
            String truncate
    ) {
    }

    private record TextInput(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResponse(List<Ranking> rankings) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Ranking(int index, double logit) {
    }
}
