package com.tongji.llm.observability.assembler;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.observability.enums.RagTranscriptStageEnum;
import com.tongji.llm.observability.enums.RagTranscriptStatusEnum;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptCandidateDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptStageDTO;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 将同一次 RAG 执行的状态与最终答案组装成运行 Transcript。
 *
 * <p>只做对象转换，不写日志、不调用模型。
 */
public final class RagRuntimeTranscriptAssembler {
    private static final String SCHEMA_VERSION = "rag-runtime-transcript-v1";

    private RagRuntimeTranscriptAssembler() {
    }

    public static RagTranscriptDTO assemble(
/*
 拼接来源RagAgentState
 把原始状态翻译成统一 Transcript 结构
 它没有 evaluator 是因为普通用户没有标准答案，不需要算命中，只记录 "召回了什么" 就行。
 FullTranscriptAssembler 才有 evaluator，因为它有 Gold 标准答案，需要在基础版上算 goldHit/goldRanks
 */
            String scope,
            RagAgentState state,
            String finalAnswer) {
        RagRetrievalResultDTO retrievalResult = state.retrievalResult();
        List<RagTranscriptStageDTO> stages = List.of(
                stage(RagTranscriptStageEnum.ORIGINAL, retrievalResult == null ? List.of() : retrievalResult.originalDocs()),
                stage(RagTranscriptStageEnum.HYDE, retrievalResult == null ? List.of() : retrievalResult.hydeDocs()),
                stage(RagTranscriptStageEnum.KEYWORD, retrievalResult == null ? List.of() : retrievalResult.keywordDocs()),
                stage(RagTranscriptStageEnum.FUSED, retrievalResult == null ? List.of() : retrievalResult.fusedDocs()),
                stage(RagTranscriptStageEnum.RERANKED, state.rerankedDocs())
        );

        return new RagTranscriptDTO(
                SCHEMA_VERSION,
                state.traceId(),
                scope,
                state.originalQuestion(),
                state.standaloneQuestion(),
                retrievalResult == null ? null : retrievalResult.hypotheticalAnswer(),
                state.currentTopK(),
                stages,
                state.steps(),
                finalAnswer,
                RagTranscriptStatusEnum.COMPLETED,
                null
        );
    }

    private static RagTranscriptStageDTO stage(RagTranscriptStageEnum stage, List<Document> documents) {
        List<Document> safeDocuments = documents == null ? List.of() : documents;
        List<RagTranscriptCandidateDTO> candidates = IntStream.range(0, safeDocuments.size())
                .mapToObj(index -> candidate(index + 1, safeDocuments.get(index)))
                .toList();
        return new RagTranscriptStageDTO(stage, candidates, null, List.of());
    }

    private static RagTranscriptCandidateDTO candidate(int rank, Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new RagTranscriptCandidateDTO(
                rank,
                stringValue(metadata.get("chunkId")),
                stringValue(metadata.get("postId")),
                document.getScore()
        );
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
