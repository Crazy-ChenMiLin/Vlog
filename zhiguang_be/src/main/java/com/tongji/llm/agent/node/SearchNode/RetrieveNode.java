package com.tongji.llm.agent.node.SearchNode;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.searchService.RagRetrievalOptions;
import com.tongji.llm.searchService.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RetrieveNode {
    private final RagRetrievalService retrievalService;

    public RagRetrievalResultDTO execute(RagAgentState state, String scope, Long postId) {
        RagRetrievalOptions options = options(state.plan(), state.graphContext());
        RagRetrievalResultDTO retrieval;
        if ("post".equalsIgnoreCase(scope)) {
            retrieval = retrievalService.retrieveForPost(postId, state.standaloneQuestion(), state.currentTopK(), options);
        } else {
            retrieval = retrievalService.retrieveGlobal(state.standaloneQuestion(), state.currentTopK(), options);
        }
        state.retrievalResult(retrieval);
        if (state.graphContext().isEmpty() && !retrieval.graphContext().isEmpty()) {
            state.graphContext(retrieval.graphContext());
        }
        return retrieval;
    }

    private RagRetrievalOptions options(RagAgentPlan plan, GraphContext graphContext) {
        return new RagRetrievalOptions(
                plan.needVectorSearch(),
                plan.needHyde(),
                plan.needKeywordSearch(),
                plan.needGraphTrace(),
                graphContext
        );
    }
}
