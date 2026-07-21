package com.tongji.llm.enhanceService;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.graphService.model.GraphEntity;
import com.tongji.llm.graphService.model.GraphRelation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    @Value("${rag.rerank.graph-relation-boost:0.35}")
    private double graphRelationBoost;

    @Value("${rag.rerank.graph-entity-boost:0.12}")
    private double graphEntityBoost;

    public List<Document> rerank(String standaloneQuestion, List<Document> fusedDocs, int topK) {
        return rerank(standaloneQuestion, fusedDocs, topK, GraphContext.empty());
    }

    public List<Document> rerank(String standaloneQuestion, List<Document> fusedDocs, int topK, GraphContext graphContext) {
        if (fusedDocs == null || fusedDocs.isEmpty() || topK <= 0) {
            return List.of();
        }
        if (!enabled || !StringUtils.hasText(apiKey) || !StringUtils.hasText(standaloneQuestion)) {
            return fallback(fusedDocs, topK, graphContext);
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
                return fallback(fusedDocs, topK, graphContext);
            }

            QuestionIntent intent = resolveIntent(standaloneQuestion, graphContext);
            List<Document> reranked = response.rankings().stream()
                    .filter(ranking -> ranking.index() >= 0 && ranking.index() < fusedDocs.size())
                    .map(ranking -> toScoredRanking(intent, graphContext, fusedDocs.get(ranking.index()), ranking))
                    .sorted(Comparator.comparingDouble(ScoredRanking::finalScore).reversed())
                    .limit(topK)
                    .map(scored -> fusedDocs.get(scored.index()))
                    .toList();
            return reranked.isEmpty() ? fallback(fusedDocs, topK, graphContext) : reranked;
        } catch (Exception e) {
            log.warn("Rerank failed, fallback to fused docs: {}", e.getMessage());
            return fallback(fusedDocs, topK, graphContext);
        }
    }

    private List<Document> fallback(List<Document> fusedDocs, int topK, GraphContext graphContext) {
        QuestionIntent intent = resolveIntent(null, graphContext);
        return fusedDocs.stream()
                .peek(document -> annotateScores(intent, graphContext, document, 0))
                .sorted(Comparator.comparingDouble((Document document) -> numericMetadata(document, "finalScore")).reversed())
                .limit(topK)
                .toList();
    }

    private ScoredRanking toScoredRanking(QuestionIntent intent, GraphContext graphContext, Document document, Ranking ranking) {
        double finalScore = annotateScores(intent, graphContext, document, ranking.logit());
        return new ScoredRanking(ranking.index(), ranking.logit(), finalScore);
    }

    private double annotateScores(QuestionIntent intent, GraphContext graphContext, Document document, double rerankScore) {
        double sectionBoost = sectionBoost(intent, document);
        double graphBoost = graphBoost(graphContext, document);
        double finalScore = rerankScore + sectionBoost + graphBoost;
        document.getMetadata().put("questionIntent", intent.name());
        document.getMetadata().put("relationIntent", graphContext == null ? null : graphContext.relationIntent());
        document.getMetadata().put("rerankScore", rerankScore);
        document.getMetadata().put("sectionBoost", sectionBoost);
        document.getMetadata().put("graphBoost", graphBoost);
        document.getMetadata().put("finalScore", finalScore);
        return finalScore;
    }

    private double graphBoost(GraphContext graphContext, Document document) {
        if (graphContext == null || graphContext.isEmpty()) {
            return 0;
        }
        String text = documentText(document);
        double boost = 0;
        for (GraphRelation relation : graphContext.relations()) {
            if (containsTerm(text, relation.source()) && containsTerm(text, relation.target())) {
                boost += graphRelationBoost;
            }
        }
        if (boost > 0) {
            return boost;
        }

        long matchedEntityCount = graphEntityTerms(graphContext).stream()
                .filter(term -> containsTerm(text, term))
                .limit(2)
                .count();
        return matchedEntityCount >= 2 ? graphEntityBoost : 0;
    }

    private Set<String> graphEntityTerms(GraphContext graphContext) {
        Set<String> terms = new LinkedHashSet<>();
        for (GraphEntity entity : graphContext.matchedEntities()) {
            if (StringUtils.hasText(entity.name())) {
                terms.add(entity.name());
            }
            entity.aliases().stream()
                    .filter(StringUtils::hasText)
                    .forEach(terms::add);
        }
        return terms;
    }

    private boolean containsTerm(String text, String term) {
        return StringUtils.hasText(term) && text.contains(term.toLowerCase(Locale.ROOT));
    }

    private String documentText(Document document) {
        StringBuilder builder = new StringBuilder();
        appendMetadataText(builder, document, "title");
        appendMetadataText(builder, document, "sectionTitle");
        builder.append(' ').append(document.getText() == null ? "" : document.getText());
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private void appendMetadataText(StringBuilder builder, Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value != null) {
            builder.append(' ').append(value);
        }
    }

    private double numericMetadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return 0;
    }

    private QuestionIntent detectIntent(String question) {
        if (!StringUtils.hasText(question)) {
            return QuestionIntent.OTHER;
        }
        String normalized = question.trim().toLowerCase();
        if (containsAny(normalized, "测试问题", "测试集", "评估", "benchmark", "召回率", "命中率")) {
            return QuestionIntent.TEST;
        }
        if (containsAny(normalized, "面试", "怎么回答", "如何回答", "回答模板")) {
            return QuestionIntent.INTERVIEW;
        }
        if (containsAny(normalized, "怎么解决", "如何解决", "怎么排查", "如何排查", "注意什么", "怎么设计", "如何设计", "方案")) {
            return QuestionIntent.SOLUTION;
        }
        if (containsAny(normalized, "是什么", "什么是", "有什么用", "作用", "原理", "区别", "为什么")) {
            return QuestionIntent.EXPLAIN;
        }
        return QuestionIntent.OTHER;
    }

    private QuestionIntent resolveIntent(String question, GraphContext graphContext) {
        QuestionIntent graphIntent = graphIntent(graphContext);
        return graphIntent == QuestionIntent.OTHER ? detectIntent(question) : graphIntent;
    }

    private QuestionIntent graphIntent(GraphContext graphContext) {
        if (graphContext == null || !StringUtils.hasText(graphContext.relationIntent())) {
            return QuestionIntent.OTHER;
        }
        return switch (graphContext.relationIntent()) {
            case "COMPARE" -> QuestionIntent.COMPARE;
            case "CAUSE" -> QuestionIntent.CAUSE;
            case "PART_OF" -> QuestionIntent.PART_OF;
            case "SOLUTION" -> QuestionIntent.SOLUTION;
            default -> QuestionIntent.OTHER;
        };
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private double sectionBoost(QuestionIntent intent, Document document) {
        Object sectionType = document.getMetadata().get("sectionType");
        String type = sectionType == null ? "" : String.valueOf(sectionType);
        return switch (intent) {
            case EXPLAIN -> switch (type) {
                case "CONCEPT" -> 0.35;
                case "SOLUTION" -> 0.15;
                case "INTERVIEW_TEMPLATE" -> 0.05;
                case "TEST_QUESTION" -> -0.35;
                case "BACKGROUND", "TITLE" -> -0.25;
                default -> 0;
            };
            case SOLUTION -> switch (type) {
                case "SOLUTION" -> 0.35;
                case "CONCEPT" -> 0.25;
                case "INTERVIEW_TEMPLATE" -> 0.10;
                case "TEST_QUESTION" -> -0.25;
                case "BACKGROUND", "TITLE" -> -0.20;
                default -> 0;
            };
            case INTERVIEW -> switch (type) {
                case "INTERVIEW_TEMPLATE" -> 0.35;
                case "CONCEPT" -> 0.10;
                case "SOLUTION" -> 0.05;
                case "TEST_QUESTION", "BACKGROUND", "TITLE" -> -0.15;
                default -> 0;
            };
            case TEST -> switch (type) {
                case "TEST_QUESTION" -> 0.35;
                case "CONCEPT" -> 0.05;
                case "BACKGROUND", "TITLE" -> -0.15;
                default -> 0;
            };
            case COMPARE, CAUSE, PART_OF -> switch (type) {
                case "CONCEPT", "SOLUTION", "INTERVIEW_TEMPLATE" -> 0.10;
                case "TEST_QUESTION", "BACKGROUND", "TITLE" -> -0.15;
                default -> 0;
            };
            case OTHER -> switch (type) {
                case "CONCEPT", "SOLUTION" -> 0.05;
                case "TITLE", "BACKGROUND" -> -0.10;
                default -> 0;
            };
        };
    }

    private String buildRerankText(Document document) {
        String text = document.getText() == null ? "" : document.getText();
        Object title = document.getMetadata().get("title");
        Object sectionTitle = document.getMetadata().get("sectionTitle");
        Object sectionType = document.getMetadata().get("sectionType");
        String titleText = title == null ? "" : String.valueOf(title);
        String sectionTitleText = sectionTitle == null ? "" : String.valueOf(sectionTitle);
        String sectionTypeText = sectionType == null ? "" : String.valueOf(sectionType);

        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(titleText)) {
            builder.append("标题：").append(titleText).append('\n');
        }
        if (StringUtils.hasText(sectionTitleText)) {
            builder.append("章节：").append(sectionTitleText).append('\n');
        }
        if (StringUtils.hasText(sectionTypeText)) {
            builder.append("章节类型：").append(sectionTypeText).append('\n');
        }
        if (!builder.isEmpty()) {
            builder.append("正文：\n");
        }
        builder.append(text);
        return builder.toString();
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

    private record ScoredRanking(int index, double rerankScore, double finalScore) {
    }

    private enum QuestionIntent {
        COMPARE,
        CAUSE,
        PART_OF,
        EXPLAIN,
        SOLUTION,
        INTERVIEW,
        TEST,
        OTHER
    }
}
