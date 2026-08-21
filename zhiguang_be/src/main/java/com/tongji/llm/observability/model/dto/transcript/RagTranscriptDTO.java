package com.tongji.llm.observability.model.dto.transcript;

import com.tongji.llm.agent.state.RagAgentStepTrace;
import com.tongji.llm.observability.enums.RagTranscriptStatusEnum;
import com.tongji.llm.observability.model.dto.evaluation.RagTranscriptEvaluationDTO;

import java.util.List;

/**
 * 一次 RAG 执行的统一 Transcript。
 *
 * <p>普通请求只记录运行事实，{@code evaluation} 为 {@code null}；
 * Benchmark 在同一份对象上附加 Gold 标注，不创建第二套顶层 schema。
 */
public record RagTranscriptDTO(
        String schemaVersion,
        String traceId,
        String scope,
        String originalQuestion,
        String standaloneQuestion,
        String hypotheticalAnswer,
        int topK,
        List<RagTranscriptStageDTO> stages,
        List<RagAgentStepTrace> steps,
        String finalAnswer,
        RagTranscriptStatusEnum status,
        RagTranscriptEvaluationDTO evaluation
) {
    public RagTranscriptDTO {
        stages = stages == null ? List.of() : List.copyOf(stages);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
