package com.tongji.llm.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.agent.state.QuestionType;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RetrievalMode;
import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.config.RagPromptService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AgentPlannerServiceTest {

    private final AgentPlannerService service = new AgentPlannerService(null, new ObjectMapper(), ragLlmProperties(), mock(RagPromptService.class));

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
    void relationQuestionKeepsRelationTypeEvenWhenPlannerChoosesHybrid() throws Exception {
        RagAgentPlan plan = service.parse("""
                {
                  "questionType": "RELATION_QA",
                  "retrievalMode": "HYBRID",
                  "needKeywordSearch": true,
                  "needVectorSearch": true,
                  "needHyde": true,
                  "needRerank": true,
                  "initialTopK": 5,
                  "reason": "关系题但模型没有显式打开 graph_trace"
                }
                """, 5);

        assertThat(plan.questionType()).isEqualTo(QuestionType.RELATION_QA);
        assertThat(plan.retrievalMode()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void badPlannerJsonThrowsSoCallerCanFallback() {
        assertThatThrownBy(() -> service.parse("not-json", 5))
                .isInstanceOf(Exception.class);
    }

    private RagLlmProperties ragLlmProperties() {
        RagLlmProperties properties = new RagLlmProperties();
        properties.setDefaultModel("test-model");
        return properties;
    }
}
