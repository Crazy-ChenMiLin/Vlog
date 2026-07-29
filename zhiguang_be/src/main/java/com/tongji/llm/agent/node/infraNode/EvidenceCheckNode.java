package com.tongji.llm.agent.node.infraNode;

import com.tongji.llm.agent.EvidenceCheckService;
import com.tongji.llm.agent.state.EvidenceResult;
import com.tongji.llm.agent.state.RagAgentState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EvidenceCheckNode {
    private final EvidenceCheckService evidenceCheckService;

    public EvidenceResult execute(RagAgentState state) {
        EvidenceResult evidence = evidenceCheckService.check(
                state.standaloneQuestion(),
                state.answerDocs(),
                state.graphContext(),
                state.currentTopK(),
                state.retryCount()
        );
        state.evidenceResult(evidence);
        return evidence;
    }
}
