package com.tongji.llm.agent.node.PlanNode;

import com.tongji.llm.agent.AgentPlannerService;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RagAgentState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlanNode {
    private final AgentPlannerService plannerService;

    public RagAgentPlan execute(RagAgentState state) {
        RagAgentPlan plan = plannerService.plan(state.standaloneQuestion(), state.currentTopK());
        state.plan(plan);
        state.currentTopK(plan.initialTopK());
        return plan;
    }
}
