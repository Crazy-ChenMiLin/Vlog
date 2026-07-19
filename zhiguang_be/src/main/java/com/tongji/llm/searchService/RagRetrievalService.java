package com.tongji.llm.searchService;

import com.tongji.llm.enhanceService.HyDEService;
import com.tongji.llm.enhanceService.RrfFusionService;
import com.tongji.llm.DTO.RagRetrievalResultDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 检索总编排：原问题向量召回、HyDE 向量召回、可选 BM25 召回，再用 RRF 融合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalService {
    private final RagIndexService indexService;
    private final RagVectorRetrievalService vectorRetrievalService;
    private final RagBm25RetrievalService bm25RetrievalService;
    private final HyDEService hydeService;
    private final RrfFusionService rrfFusion;

    @Value("${rag.retrieval.bm25-enabled:false}")
    private boolean bm25Enabled;

    public RagRetrievalResultDTO retrieveForPost(long postId, String question, int topK) {
        indexService.ensureIndexed(postId);
        return retrieveInternal(postId, question, topK);
    }

    public RagRetrievalResultDTO retrieveGlobal(String question, int topK) {
        return retrieveInternal(null, question, topK);
    }

    private RagRetrievalResultDTO retrieveInternal(Long postId, String question, int topK) {
        // HyDE 只生成向量召回用的辅助查询；BM25 仍使用用户问题本身，避免生成文本污染关键词召回。
        String hypotheticalAnswer = hydeService.generateHypotheticalAnswer(question);
        List<Document> originalDocs = vectorRetrievalService.search(postId, question, topK);
        List<Document> hydeDocs = StringUtils.hasText(hypotheticalAnswer)
                ? vectorRetrievalService.search(postId, hypotheticalAnswer, topK)
                : List.of();
        List<Document> keywordDocs = bm25Enabled
                ? bm25RetrievalService.search(postId, question, topK)
                : List.of();

        // 只有一路召回有结果时不需要 RRF；多路召回时再按排名融合，避免空列表影响分数。
        List<List<Document>> rankedLists = nonEmptyLists(originalDocs, hydeDocs, keywordDocs);
        List<Document> fusedDocs = rankedLists.size() <= 1
                ? rankedLists.stream().findFirst().orElse(List.of()).stream().limit(topK).toList()
                : rrfFusion.fuse(rankedLists, topK);

        String scope = postId == null ? "global" : "post:" + postId;
        log.info("RAG retrieval scope={} original={} hyde={} keyword={} fused={}",
                scope, chunkIds(originalDocs), chunkIds(hydeDocs), chunkIds(keywordDocs), chunkIds(fusedDocs));
        return new RagRetrievalResultDTO(
                hypotheticalAnswer,
                RagVectorRetrievalService.MIN_SIMILARITY_SCORE,
                originalDocs,
                hydeDocs,
                keywordDocs,
                fusedDocs
        );
    }

    @SafeVarargs
    private final List<List<Document>> nonEmptyLists(List<Document>... lists) {
        List<List<Document>> result = new ArrayList<>();
        for (List<Document> list : lists) {
            if (list != null && !list.isEmpty()) {
                result.add(list);
            }
        }
        return result;
    }

    private List<String> chunkIds(List<Document> docs) {
        return docs.stream()
                .map(document -> String.valueOf(document.getMetadata().get("chunkId")))
                .toList();
    }
}
