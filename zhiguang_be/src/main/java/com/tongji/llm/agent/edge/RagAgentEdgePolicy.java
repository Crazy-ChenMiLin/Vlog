package com.tongji.llm.agent.edge;

import com.tongji.llm.agent.state.EvidenceAction;
import com.tongji.llm.agent.state.EvidenceResult;
import com.tongji.llm.agent.state.QuestionType;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RagAgentState;
import org.springframework.stereotype.Component;

/**
 * Conditional edges for the RAG agent graph.
 *
 * <p>The nodes do the work, while this policy decides whether the next optional edge
 * should be taken based on the current plan/state.</p>
 */
@Component
public class RagAgentEdgePolicy {

    public boolean shouldDirectAnswer(RagAgentPlan plan) {
        return plan.questionType() == QuestionType.CHAT || plan.needDirectAnswer();
    }

    public boolean shouldQueryGraph(RagAgentPlan plan) {
        return plan.needGraphTrace();
    }

    public boolean shouldRerank(RagAgentState state) {
        return state.plan().needRerank();
    }

    public boolean shouldExpandTopK(RagAgentState state, EvidenceResult evidence) {
        return evidence != null
                && !evidence.sufficient()
                && evidence.suggestedAction() == EvidenceAction.EXPAND_TOP_K
                && state.retryCount() == 0
                && state.currentTopK() < 10;
    }
}
