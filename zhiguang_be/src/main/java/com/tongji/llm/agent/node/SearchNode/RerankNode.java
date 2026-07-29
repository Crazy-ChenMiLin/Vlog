package com.tongji.llm.agent.node.SearchNode;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.enhanceService.RerankService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class RerankNode {
    private final RerankService rerankService;

    public List<Document> execute(RagAgentState state) {
        RagRetrievalResultDTO retrieval = state.retrievalResult();
        List<Document> reranked = rerankService.rerank(
                state.standaloneQuestion(),
                retrieval.fusedDocs(),
                state.currentTopK(),
                retrieval.graphContext()
        );
        return applyAnswerDocs(state, reranked);
    }

    public List<Document> skip(RagAgentState state) {
        RagRetrievalResultDTO retrieval = state.retrievalResult();
        return applyAnswerDocs(state, retrieval.fusedDocs().stream().limit(state.currentTopK()).toList());
    }

    private List<Document> applyAnswerDocs(RagAgentState state, List<Document> reranked) {
        RagRetrievalResultDTO retrieval = state.retrievalResult();
        if (reranked == null) {
            reranked = retrieval.fusedDocs().stream().limit(state.currentTopK()).toList();
        }
        state.rerankedDocs(reranked);
        state.answerDocs(reranked.stream().limit(Math.min(5, state.currentTopK())).toList());
        return reranked;
    }
}
