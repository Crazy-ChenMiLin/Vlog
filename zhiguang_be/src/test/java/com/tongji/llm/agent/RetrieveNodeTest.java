package com.tongji.llm.agent;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.agent.node.SearchNode.RetrieveNode;
import com.tongji.llm.agent.state.QuestionType;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.agent.state.RetrievalMode;
import com.tongji.llm.config.RagConfig;
import com.tongji.llm.searchService.RagRetrievalOptions;
import com.tongji.llm.searchService.RagRetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrieveNodeTest {

    @Mock
    private RagRetrievalService retrievalService;

    @Test
    void nacosBm25SwitchOverridesPlannerKeywordIntent() {
        RagConfig ragConfig = new RagConfig();
        ragConfig.getRetrieval().setBm25Enabled(false);
        RetrieveNode node = new RetrieveNode(retrievalService, ragConfig);
        RagAgentState state = new RagAgentState("question", "question", 5);
        state.plan(new RagAgentPlan(
                QuestionType.NORMAL_QA, RetrievalMode.HYBRID, false,
                true, true, true, false, true, 5, "test"
        ));
        when(retrievalService.retrieveGlobal(eq("question"), eq(5), any(RagRetrievalOptions.class)))
                .thenReturn(new RagRetrievalResultDTO(null, 0, List.of(), List.of(), List.of(), List.of()));

        node.execute(state, "global", null);

        ArgumentCaptor<RagRetrievalOptions> options = ArgumentCaptor.forClass(RagRetrievalOptions.class);
        verify(retrievalService).retrieveGlobal(eq("question"), eq(5), options.capture());
        assertThat(options.getValue().useBm25()).isFalse();
    }
}
