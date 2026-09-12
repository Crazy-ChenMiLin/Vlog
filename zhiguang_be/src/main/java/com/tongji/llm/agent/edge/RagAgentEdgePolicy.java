package com.tongji.llm.agent.edge;

import com.tongji.llm.agent.state.EvidenceAction;
import com.tongji.llm.agent.state.EvidenceResult;
import com.tongji.llm.agent.state.QuestionType;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.agent.state.RetrievalMode;
import org.springframework.stereotype.Component;

/**
 * RAG Agent 图的条件边策略。
 *
 * <p>节点负责执行具体任务，本策略根据计划和运行状态判断是否进入可选分支。</p>
 */
@Component
public class RagAgentEdgePolicy {

    public boolean shouldDirectAnswer(RagAgentPlan plan) {
        if (plan.questionType() == QuestionType.CHAT) {
            return true;
        }
        // 直接回答只用于闲聊或明确无需检索的请求；即使 Planner 误置标志，技术和关系类问题仍须进入检索链路。
        return plan.needDirectAnswer()
                && plan.retrievalMode() == RetrievalMode.NONE
                && !plan.needKeywordSearch()
                && !plan.needVectorSearch()
                && !plan.needHyde()
                && !plan.needGraphTrace();
    }

    public boolean shouldQueryGraph(RagAgentPlan plan) {
        return plan.questionType() == QuestionType.RELATION_QA || plan.needGraphTrace();
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
