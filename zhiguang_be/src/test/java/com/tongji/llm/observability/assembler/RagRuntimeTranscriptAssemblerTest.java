package com.tongji.llm.observability.assembler;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.observability.enums.RagTranscriptStageEnum;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagRuntimeTranscriptAssemblerTest {

    @Test
    void assemblesFiveRuntimeStagesFromOneAgentState() {
        Document original = document("post-1", "post-1#0");
        Document gold = document("post-2", "post-2#1");
        RagAgentState state = new RagAgentState("原问题", "改写问题", 5);
        state.evalRunId("benchmark-run-001");
        state.retrievalResult(new RagRetrievalResultDTO(
                "HyDE 假设答案", 0.30,
                List.of(original), List.of(gold), List.of(), List.of(gold)
        ));
        state.rerankedDocs(List.of(gold));

        RagTranscriptDTO transcript = RagRuntimeTranscriptAssembler.assemble(
                "global", state, "最终回答"
        );

        assertThat(transcript.traceId()).isEqualTo(state.traceId());
        assertThat(transcript.originalQuestion()).isEqualTo("原问题");
        assertThat(transcript.standaloneQuestion()).isEqualTo("改写问题");
        assertThat(transcript.finalAnswer()).isEqualTo("最终回答");
        assertThat(transcript.stages()).hasSize(5);
        assertThat(transcript.stages())
                .filteredOn(stage -> stage.stage() == RagTranscriptStageEnum.HYDE)
                .singleElement()
                .satisfies(stage -> assertThat(stage.candidates())
                        .extracting(candidate -> candidate.chunkId())
                        .containsExactly("post-2#1"));
    }

    private Document document(String postId, String chunkId) {
        return new Document("测试正文", Map.of("postId", postId, "chunkId", chunkId));
    }
}
