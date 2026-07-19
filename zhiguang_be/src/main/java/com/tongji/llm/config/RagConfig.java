package com.tongji.llm.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "rag")
public class RagConfig {
    @Valid
    private Answer answer = new Answer();

    @Data
    public static class Answer {
        @Min(128)
        @Max(4096)
        private int maxTokens = 1024;
    }
}
