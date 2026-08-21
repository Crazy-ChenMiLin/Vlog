package com.tongji.benchmark.assembler;

import com.tongji.benchmark.evaluator.StageHitEvaluator;
import com.tongji.benchmark.model.dto.BenchmarkCaseDTO;
import com.tongji.llm.observability.model.dto.evaluation.RagTranscriptEvaluationDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptStageDTO;

import java.util.List;
import java.util.Set;

/**
 * 在同一份运行 Transcript 上附加 Benchmark Gold 标注。
 *
 * <p>它不会重新执行 RAG，也不会改变已经记录的候选结果或最终答案。
 */
public final class FullTranscriptAssembler {

    private FullTranscriptAssembler() {
    }

    public static RagTranscriptDTO attachEvaluation(
            // 拼接来源：已经组装好的 RagTranscriptDTO（基础版）。直接使用rag生成的transcript作为原料
            RagTranscriptDTO transcript,
            BenchmarkCaseDTO benchmarkCase,
            String runId,
            String datasetVersion) {
        Set<String> expectedChunkIds = Set.copyOf(benchmarkCase.expectedChunkIds());
        List<RagTranscriptStageDTO> annotatedStages = transcript.stages().stream()

                //干的事：在基础版上附加 Gold 评测标注（goldHit、evaluation）
                .map(stage -> StageHitEvaluator.annotate(stage, expectedChunkIds))
                .toList();

        RagTranscriptEvaluationDTO evaluation = new RagTranscriptEvaluationDTO(
                runId,
                benchmarkCase.caseId(),
                datasetVersion,
                datasetVersion,
                benchmarkCase.expectedChunkIds()
        );
        //RagTranscriptDTO（评测版）返回给python代码
        return new RagTranscriptDTO(
                transcript.schemaVersion(),
                transcript.traceId(),
                transcript.scope(),
                transcript.originalQuestion(),
                transcript.standaloneQuestion(),
                transcript.hypotheticalAnswer(),
                transcript.topK(),
                annotatedStages,
                transcript.steps(),
                transcript.finalAnswer(),
                transcript.status(),
                evaluation
        );
    }
}
