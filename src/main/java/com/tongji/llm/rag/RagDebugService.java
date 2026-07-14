package com.tongji.llm.rag;

import com.tongji.llm.DTO.RagRetrievalDebugDTO;
import com.tongji.llm.DTO.RagRetrievalResultDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 将检索结果转换为便于观察的调试响应，不参与最终答案生成。
 */
@Service
@RequiredArgsConstructor
public class RagDebugService {
    private final RagRetrievalService retrievalService;

    public RagRetrievalDebugDTO debugPostRetrieval(long postId, String question, int topK) {
        RagRetrievalResultDTO result = retrievalService.retrieveForPost(postId, question, topK);
        return toDebugResult("post", postId, question, result);
    }

    public RagRetrievalDebugDTO debugGlobalRetrieval(String question, int topK) {
        RagRetrievalResultDTO result = retrievalService.retrieveGlobal(question, topK);
        return toDebugResult("global", null, question, result);
    }

    private RagRetrievalDebugDTO toDebugResult(
            String scope,
            Long postId,
            String question,
            RagRetrievalResultDTO result) {
        return new RagRetrievalDebugDTO(
                scope,
                postId,
                question,
                result.hypotheticalAnswer(),
                result.similarityThreshold(),
                toDebugChunks(result.originalDocs()),
                toDebugChunks(result.hydeDocs()),
                toDebugChunks(result.fusedDocs())
        );
    }

    private List<RagRetrievalDebugDTO.RetrievedChunk> toDebugChunks(List<Document> docs) {
        return IntStream.range(0, docs.size())
                .mapToObj(index -> toDebugChunk(index + 1, docs.get(index)))
                .toList();
    }

    private RagRetrievalDebugDTO.RetrievedChunk toDebugChunk(int rank, Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new RagRetrievalDebugDTO.RetrievedChunk(
                rank,
                stringValue(metadata.get("postId")),
                stringValue(metadata.get("chunkId")),
                stringValue(metadata.get("title")),
                integerValue(metadata.get("position")),
                document.getScore(),
                preview(document.getText())
        );
    }

    private String preview(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
