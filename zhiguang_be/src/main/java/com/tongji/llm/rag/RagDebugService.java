package com.tongji.llm.rag;

import com.tongji.llm.DTO.RagRetrievalDebugDTO;
import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.DTO.RagRetrievalResultRankDTO;
import com.tongji.llm.enhanceService.RerankService;
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
    private final RerankService rerankService;

    //先混合检索到文章
    public RagRetrievalDebugDTO debugPostRetrieval(long postId, String question, int topK) {
        RagRetrievalResultDTO result = retrievalService.retrieveForPost(postId, question, topK);
    //然后rerank文章
        return toDebugResult("post", postId, question, result);
    }

    //先混合检索到文章
    public RagRetrievalDebugDTO debugGlobalRetrieval(String question, int topK) {
        RagRetrievalResultDTO result = retrievalService.retrieveGlobal(question, topK);
    //然后rerank文章
        return toDebugResult("global", null, question, result);
    }

    private RagRetrievalDebugDTO toDebugResult(
            String scope,
            Long postId,
            String question,
            RagRetrievalResultDTO result) {
        RagRetrievalResultRankDTO ranked = rankResult(question, result);
        return new RagRetrievalDebugDTO(
                scope,
                postId,
                question,
                result.hypotheticalAnswer(),
                result.similarityThreshold(),
                toDebugChunks(result.originalDocs()),
                toDebugChunks(result.hydeDocs()),
                toDebugChunks(result.fusedDocs()),
                toDebugChunks(ranked.rerankedDocs()),
                toDebugChunks(ranked.answerDocs())
        );
    }

    private RagRetrievalResultRankDTO rankResult(String question, RagRetrievalResultDTO result) {
        int topK = result.fusedDocs().size();
        List<Document> rerankedDocs = rerankService.rerank(question, result.fusedDocs(), topK);
        if (rerankedDocs == null) {
            rerankedDocs = result.fusedDocs();
        }
        return new RagRetrievalResultRankDTO(result, rerankedDocs, rerankedDocs);
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
                stringValue(metadata.get("sectionTitle")),
                stringValue(metadata.get("sectionType")),
                stringValue(metadata.get("questionIntent")),
                doubleValue(metadata.get("rerankScore")),
                doubleValue(metadata.get("sectionBoost")),
                doubleValue(metadata.get("finalScore")),
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

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? null : Double.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
