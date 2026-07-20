package com.tongji.llm.searchService;

import com.tongji.llm.enhanceService.HyDEService;
import com.tongji.llm.enhanceService.RrfFusionService;
import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.graphService.GraphContextService;
import com.tongji.llm.graphService.model.GraphContext;
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
    private final GraphContextService graphContextService;
    private final HyDEService hydeService;
    private final RrfFusionService rrfFusion;

    @Value("${rag.retrieval.bm25-enabled:false}")
    private boolean bm25Enabled;
    @Value("${rag.retrieval.graph-enabled:false}")
    private boolean graphEnabled;

    public RagRetrievalResultDTO retrieveForPost(long postId, String question, int topK) {
        indexService.ensureIndexed(postId);
        return retrieveInternal(postId, question, topK);
    }

    public RagRetrievalResultDTO retrieveGlobal(String question, int topK) {
        return retrieveInternal(null, question, topK);
    }

    private RagRetrievalResultDTO retrieveInternal(Long postId, String question, int topK) {
        GraphContext graphContext = graphEnabled ? graphContextService.build(question) : GraphContext.empty();
        String bm25Query = graphEnabled && !graphContext.isEmpty() ? graphContext.keywordQuery(question) : question;

        // HyDE 只生成向量召回用的辅助查询；GraphContext 打开时用关系摘要让假设答案更聚焦。
        String hydeQuestion = enrichHydeQuestion(question, graphContext);
        String hypotheticalAnswer = hydeService.generateHypotheticalAnswer(hydeQuestion);
        List<Document> originalDocs = vectorRetrievalService.search(postId, question, topK);
        List<Document> hydeDocs = StringUtils.hasText(hypotheticalAnswer)
                ? vectorRetrievalService.search(postId, hypotheticalAnswer, topK)
                : List.of();
        List<Document> keywordDocs = bm25Enabled
                ? bm25RetrievalService.search(postId, bm25Query, topK)
                : List.of();

        // 只有一路召回有结果时不需要 RRF；多路召回时再按排名融合，避免空列表影响分数。
        List<List<Document>> rankedLists = nonEmptyLists(originalDocs, hydeDocs, keywordDocs);
        List<Document> fusedDocs = rankedLists.size() <= 1
                ? rankedLists.stream().findFirst().orElse(List.of()).stream().limit(topK).toList()
                : rrfFusion.fuse(rankedLists, topK);

        String scope = postId == null ? "global" : "post:" + postId;
        log.info("RAG retrieval scope={} graphEntities={} original={} hyde={} keyword={} fused={}",
                scope, graphContext.matchedEntities(), chunkIds(originalDocs), chunkIds(hydeDocs), chunkIds(keywordDocs), chunkIds(fusedDocs));
        return new RagRetrievalResultDTO(
                hypotheticalAnswer,
                RagVectorRetrievalService.MIN_SIMILARITY_SCORE,
                graphContext,
                originalDocs,
                hydeDocs,
                keywordDocs,
                fusedDocs
        );
    }

    private String enrichHydeQuestion(String question, GraphContext graphContext) {
        if (!graphEnabled || graphContext.isEmpty() || !StringUtils.hasText(graphContext.relationSummary())) {
            return question;
        }
        return question.trim()
                + "\n\n已知知识图谱关系：\n"
                + graphContext.relationSummary();
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
