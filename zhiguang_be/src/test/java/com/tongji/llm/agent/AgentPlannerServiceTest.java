package com.tongji.llm.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.agent.state.QuestionType;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RetrievalMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPlannerServiceTest {

    private final AgentPlannerService service = new AgentPlannerService(null, new ObjectMapper());

    @Test
    void parsePlannerJson() throws Exception {
        RagAgentPlan plan = service.parse("""
                {
                  "questionType": "RELATION_QA",
                  "retrievalMode": "GRAPH_AUGMENTED_HYBRID",
                  "needDirectAnswer": false,
                  "needKeywordSearch": true,
                  "needVectorSearch": true,
                  "needHyde": true,
                  "needGraphTrace": true,
                  "needRerank": true,
                  "initialTopK": 5,
                  "reason": "关系题"
                }
                """, 5);

        assertThat(plan.questionType()).isEqualTo(QuestionType.RELATION_QA);
        assertThat(plan.retrievalMode()).isEqualTo(RetrievalMode.GRAPH_AUGMENTED_HYBRID);
        assertThat(plan.needGraphTrace()).isTrue();
        assertThat(plan.needRerank()).isTrue();
    }

    @Test
    void badPlannerJsonThrowsSoCallerCanFallback() {
        assertThatThrownBy(() -> service.parse("not-json", 5))
                .isInstanceOf(Exception.class);
    }
}
