package com.tongji.llm.agent.node.graphNode;

import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.graphService.MainService;
import com.tongji.llm.graphService.model.GraphContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GraphTraceNode {
    private final MainService graphService;

    public GraphContext execute(RagAgentState state) {
        GraphContext graphContext = graphService.build(state.standaloneQuestion());
        state.graphContext(graphContext);
        return graphContext;
    }
}
