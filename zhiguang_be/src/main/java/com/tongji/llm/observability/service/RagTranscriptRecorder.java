package com.tongji.llm.observability.service;

import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.observability.assembler.RagRuntimeTranscriptAssembler;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.scheduler.Schedulers;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 在回答流结束后异步记录正式 RAG 的运行 Transcript。
 */
@Slf4j
@Service
public class RagTranscriptRecorder {

    public void recordCompleted(String scope, RagAgentState state, String finalAnswer) {
        //简单接口
        //复杂接口
        Schedulers.boundedElastic().schedule(() -> write(scope, state, finalAnswer));
    }

    private void write(String scope, RagAgentState state, String finalAnswer) {
        RagTranscriptDTO transcript = RagRuntimeTranscriptAssembler.assemble(scope, state, finalAnswer);
        log.info("rag_runtime_transcript",
                kv("event_type", "rag_runtime_transcript"),
                kv("trace_id", transcript.traceId()),
                kv("eval_run_id", state.evalRunId()),
                kv("scope", transcript.scope()),
                kv("status", transcript.status().name()),
                kv("transcript", transcript));
    }
}
