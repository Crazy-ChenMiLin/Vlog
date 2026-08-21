package com.tongji.benchmark.service;

import com.tongji.benchmark.assembler.FullTranscriptAssembler;
import com.tongji.benchmark.model.dto.BenchmarkCaseDTO;
import com.tongji.benchmark.model.dto.BenchmarkEvaluationContextDTO;
import com.tongji.llm.chat.RagQueryService;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 编排一道 Gold 题的一次完整内部评测。
 */
@Service
@RequiredArgsConstructor
public class BenchmarkSingleCaseService {
    private final BenchmarkCaseService benchmarkCaseService;
    private final RagQueryService ragQueryService;

    public Mono<RagTranscriptDTO> execute(BenchmarkEvaluationContextDTO context, int topK) {
        BenchmarkCaseDTO benchmarkCase = benchmarkCaseService.getRequiredCase(context.caseId());
        return ragQueryService.generateGlobalTranscript(
                        benchmarkCase.question(),
                        topK,
                        context.runId()
                )
                .map(transcript -> FullTranscriptAssembler.attachEvaluation(
                        transcript,
                        benchmarkCase,
                        context.runId(),
                        context.datasetVersion()
                ));
    }
}
