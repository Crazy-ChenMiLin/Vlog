package com.tongji.llm.chat;

import com.tongji.llm.agent.model.RagAgentStepTrace;
import com.tongji.llm.chat.dto.AgentStepEventDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentStepEventDTOTest {

    @Test
    void mapsKnownStepNameToReadableTitle() {
        AgentStepEventDTO event = AgentStepEventDTO.from(
                "trace-1",
                new RagAgentStepTrace("graph_trace", "QUERY_NEO4J", true, 35, "relations=3")
        );

        assertThat(event.title()).isEqualTo("查询 Neo4j 图谱线索");
        assertThat(event.stepName()).isEqualTo("graph_trace");
    }

    @Test
    void unknownStepNameUsesFallbackTitle() {
        AgentStepEventDTO event = AgentStepEventDTO.from(
                "trace-1",
                new RagAgentStepTrace("custom_step", "CUSTOM", true, 1, "ok")
        );

        assertThat(event.title()).isEqualTo("执行 Agent 步骤");
    }
}
