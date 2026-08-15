package com.tongji.llm.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@RefreshScope
@ConfigurationProperties(prefix = "rag")
public class RagConfig {
    @Valid
    private Answer answer = new Answer();
    private Retrieval retrieval = new Retrieval();
    private Graph graph = new Graph();
    private Rerank rerank = new Rerank();

    @Data
    public static class Answer {
        @Min(128)
        @Max(4096)
        private int maxTokens = 1024;
    }

    @Data
    public static class Retrieval {
        private boolean bm25Enabled = true;
        private boolean graphEnabled;
        @Min(1)
        @Max(10)
        private int candidateMultiplier = 2;
        @Min(1)
        @Max(100)
        private int maxCandidates = 20;
    }

    @Data
    public static class Graph {
        private boolean understandingEnabled = true;
    }

    @Data
    public static class Rerank {
        private boolean enabled = true;
        private String baseUrl;
        private String apiKey;
        private String model;
        private String path;
        private double graphRelationBoost = 0.35;
        private double graphEntityBoost = 0.12;
    }
}
