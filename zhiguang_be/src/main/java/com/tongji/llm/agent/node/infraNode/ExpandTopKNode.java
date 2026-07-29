package com.tongji.llm.agent.node.infraNode;

import com.tongji.llm.agent.state.RagAgentState;
import org.springframework.stereotype.Component;

@Component
public class ExpandTopKNode {
    public String execute(RagAgentState state) {
        state.incrementRetryCount();
        state.currentTopK(Math.min(10, Math.max(state.currentTopK() + 1, 10)));
        return "Expand topK to " + state.currentTopK();
    }
}
