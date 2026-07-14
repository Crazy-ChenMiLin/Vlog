package com.tongji.llm.DTO;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 检索流水线的完整结果，供问答服务和调试服务共同使用。
 */
public record RagRetrievalResultDTO(
        String hypotheticalAnswer,
        double similarityThreshold,
        List<Document> originalDocs,
        List<Document> hydeDocs,
        List<Document> fusedDocs
) {
    public RagRetrievalResultDTO {
        originalDocs = List.copyOf(originalDocs);
        hydeDocs = List.copyOf(hydeDocs);
        fusedDocs = List.copyOf(fusedDocs);
    }
}
