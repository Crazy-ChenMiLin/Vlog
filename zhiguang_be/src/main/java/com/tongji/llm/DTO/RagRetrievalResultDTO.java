package com.tongji.llm.DTO;

import com.tongji.llm.graphService.model.GraphContext;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索流水线的完整结果，供问答服务和调试服务共同使用。
 */
public record RagRetrievalResultDTO(
        String hypotheticalAnswer,
        double similarityThreshold,
        GraphContext graphContext,
        List<Document> originalDocs,
        List<Document> hydeDocs,
        List<Document> keywordDocs,
        List<Document> fusedDocs
) {
    public RagRetrievalResultDTO(
            String hypotheticalAnswer,
            double similarityThreshold,
            List<Document> originalDocs,
            List<Document> hydeDocs,
            List<Document> keywordDocs,
            List<Document> fusedDocs) {
        this(hypotheticalAnswer, similarityThreshold, GraphContext.empty(), originalDocs, hydeDocs, keywordDocs, fusedDocs);
    }

    public RagRetrievalResultDTO {
        graphContext = graphContext == null ? GraphContext.empty() : graphContext;
        originalDocs = List.copyOf(originalDocs);
        hydeDocs = List.copyOf(hydeDocs);
        keywordDocs = List.copyOf(keywordDocs);
        fusedDocs = List.copyOf(fusedDocs);
    }
}
