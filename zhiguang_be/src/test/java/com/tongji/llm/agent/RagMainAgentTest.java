package com.tongji.llm.agent;

import com.tongji.llm.DTO.RagRetrievalResultDTO;
import com.tongji.llm.agent.edge.RagAgentEdgePolicy;
import com.tongji.llm.agent.node.anwserNode.DirectAnswerNode;
import com.tongji.llm.agent.node.infraNode.EvidenceCheckNode;
import com.tongji.llm.agent.node.infraNode.ExpandTopKNode;
import com.tongji.llm.agent.node.graphNode.GraphTraceNode;
import com.tongji.llm.agent.node.PlanNode.PlanNode;
import com.tongji.llm.agent.node.SearchNode.RerankNode;
import com.tongji.llm.agent.node.SearchNode.RetrieveNode;
import com.tongji.llm.agent.state.EvidenceAction;
import com.tongji.llm.agent.state.EvidenceResult;
import com.tongji.llm.agent.state.QuestionType;
import com.tongji.llm.agent.state.RagAgentPlan;
import com.tongji.llm.agent.state.RagAgentState;
import com.tongji.llm.agent.state.RetrievalMode;
import com.tongji.llm.config.RagLlmProperties;
import com.tongji.llm.config.RagConfig;
import com.tongji.llm.enhanceService.RerankService;
import com.tongji.llm.graphService.MainService;
import com.tongji.llm.graphService.model.GraphContext;
import com.tongji.llm.graphService.model.GraphEntity;
import com.tongji.llm.graphService.model.GraphRelation;
import com.tongji.llm.searchService.RagRetrievalOptions;
import com.tongji.llm.searchService.RagRetrievalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagMainAgentTest {

    @Mock
    private AgentPlannerService plannerService;
    @Mock
    private EvidenceCheckService evidenceCheckService;
    @Mock
    private MainService graphService;
    @Mock
    private RagRetrievalService retrievalService;
    @Mock
    private RerankService rerankService;
    @Mock
    private ChatClient chatClient;

    @Test
    void relationQuestionQueriesGraphBeforeRetrievalAndExpandsTopKOnce() {
        RagAgentPlan plan = new RagAgentPlan(
                QuestionType.RELATION_QA,
                RetrievalMode.GRAPH_AUGMENTED_HYBRID,
                false,
                true,
                true,
                true,
                true,
                true,
                5,
                "关系题"
        );
        GraphContext graphContext = graphContext();
        Document doc5 = document("1#5");
        Document doc10 = document("1#10");
        RagRetrievalResultDTO retrieval5 = retrieval(List.of(doc5), graphContext);
        RagRetrievalResultDTO retrieval10 = retrieval(List.of(doc10), graphContext);

        when(plannerService.plan("缓存命中和缓存击穿有什么区别", 5)).thenReturn(plan);
        when(graphService.build("缓存命中和缓存击穿有什么区别")).thenReturn(graphContext);
        when(retrievalService.retrieveGlobal(eq("缓存命中和缓存击穿有什么区别"), eq(5), any(RagRetrievalOptions.class)))
                .thenReturn(retrieval5);
        when(retrievalService.retrieveGlobal(eq("缓存命中和缓存击穿有什么区别"), eq(10), any(RagRetrievalOptions.class)))
                .thenReturn(retrieval10);
        when(rerankService.rerank(eq("缓存命中和缓存击穿有什么区别"), eq(List.of(doc5)), eq(5), eq(graphContext)))
                .thenReturn(List.of(doc5));
        when(rerankService.rerank(eq("缓存命中和缓存击穿有什么区别"), eq(List.of(doc10)), eq(10), eq(graphContext)))
                .thenReturn(List.of(doc10));
        when(evidenceCheckService.check(eq("缓存命中和缓存击穿有什么区别"), eq(List.of(doc5)), eq(graphContext), eq(5), eq(0)))
                .thenReturn(new EvidenceResult(false, 0.4, "不足", EvidenceAction.EXPAND_TOP_K));
        when(evidenceCheckService.check(eq("缓存命中和缓存击穿有什么区别"), eq(List.of(doc10)), eq(graphContext), eq(10), eq(1)))
                .thenReturn(EvidenceResult.sufficient("足够"));

        RagMainAgent agent = createAgent();
        RagAgentState state = agent.run("global", null, "缓存命中和缓存击穿有什么区别", "缓存命中和缓存击穿有什么区别", 5);

        assertThat(state.retryCount()).isEqualTo(1);
        assertThat(state.currentTopK()).isEqualTo(10);
        assertThat(state.answerDocs()).containsExactly(doc10);
        assertThat(state.evidenceResult().sufficient()).isTrue();
        assertThat(state.steps()).extracting("stepName")
                .contains("plan", "graph_trace", "retrieve", "evidence_check", "retry", "rerank");

        InOrder inOrder = inOrder(graphService, retrievalService);
        inOrder.verify(graphService).build("缓存命中和缓存击穿有什么区别");
        inOrder.verify(retrievalService).retrieveGlobal(eq("缓存命中和缓存击穿有什么区别"), eq(5), any(RagRetrievalOptions.class));
        verify(retrievalService).retrieveGlobal(eq("缓存命中和缓存击穿有什么区别"), eq(10), any(RagRetrievalOptions.class));
    }

    @Test
    void relationQuestionDoesNotShortCircuitWhenPlannerMistakenlyRequestsDirectAnswer() {
        String question = "Redis 和 MySQL 是什么关系？";
        RagAgentPlan plan = new RagAgentPlan(
                QuestionType.RELATION_QA,
                RetrievalMode.GRAPH_AUGMENTED_HYBRID,
                true,
                true,
                true,
                true,
                true,
                true,
                5,
                "Planner mistakenly marked relation question as direct answer."
        );
        GraphContext graphContext = graphContext();
        Document doc = document("1#relation");
        RagRetrievalResultDTO retrieval = retrieval(List.of(doc), graphContext);

        when(plannerService.plan(question, 5)).thenReturn(plan);
        when(graphService.build(question)).thenReturn(graphContext);
        when(retrievalService.retrieveGlobal(eq(question), eq(5), any(RagRetrievalOptions.class)))
                .thenReturn(retrieval);
        when(rerankService.rerank(eq(question), eq(List.of(doc)), eq(5), eq(graphContext)))
                .thenReturn(List.of(doc));
        when(evidenceCheckService.check(eq(question), eq(List.of(doc)), eq(graphContext), eq(5), eq(0)))
                .thenReturn(EvidenceResult.sufficient("关系证据足够"));

        RagMainAgent agent = createAgent();
        RagAgentState state = agent.run("global", null, question, question, 5);

        assertThat(state.finalAnswer()).isNull();
        assertThat(state.steps()).extracting("stepName")
                .contains("graph_trace", "retrieve", "rerank", "evidence_check")
                .doesNotContain("direct_answer");
        verify(graphService).build(question);
        verify(retrievalService).retrieveGlobal(eq(question), eq(5), any(RagRetrievalOptions.class));
    }

    private RagMainAgent createAgent() {
        return new RagMainAgent(
                new PlanNode(plannerService),
                new EvidenceCheckNode(evidenceCheckService),
                new GraphTraceNode(graphService),
                new RetrieveNode(retrievalService, ragConfig()),
                new RerankNode(rerankService),
                new ExpandTopKNode(),
                new DirectAnswerNode(chatClient, ragLlmProperties()),
                new RagAgentEdgePolicy()
        );
    }

    private RagLlmProperties ragLlmProperties() {
        RagLlmProperties properties = new RagLlmProperties();
        properties.setDefaultModel("test-model");
        return properties;
    }

    private RagConfig ragConfig() {
        return new RagConfig();
    }

    private RagRetrievalResultDTO retrieval(List<Document> docs, GraphContext graphContext) {
        return new RagRetrievalResultDTO(null, 0.3, graphContext, docs, List.of(), List.of(), docs);
    }

    private Document document(String chunkId) {
        return new Document("正文 " + chunkId, Map.of("postId", "1", "chunkId", chunkId));
    }

    private GraphContext graphContext() {
        return new GraphContext(
                List.of(new GraphEntity("缓存命中", List.of("缓存命中"))),
                List.of(new GraphRelation("缓存命中", "COMPARE_WITH", "缓存击穿", "缓存命中和缓存击穿需要对比理解")),
                List.of("Redis 缓存问题"),
                List.of("缓存命中", "缓存击穿")
        );
    }
}
