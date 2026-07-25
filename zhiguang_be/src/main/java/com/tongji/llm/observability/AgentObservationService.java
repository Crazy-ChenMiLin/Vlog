package com.tongji.llm.observability;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tongji.llm.agent.model.EvidenceResult;
import com.tongji.llm.agent.model.RagAgentState;
import com.tongji.llm.agent.model.RagAgentStepTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent observability sink for Kibana.
 *
 * <p>The UI-facing {@code agent_step} SSE event is for users, while this service is for
 * engineering observation: latency, retry, evidence score, selected tool path and failures.
 * It writes small structured documents directly into Elasticsearch so the project can get
 * useful Kibana visibility even when Logstash is not available yet.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentObservationService {
    private final ElasticsearchClient elasticsearchClient;

    @Value("${rag.observability.elasticsearch.enabled:true}")
    private boolean enabled;

    @Value("${rag.observability.elasticsearch.index-prefix:zhiguang-agent-observability}")
    private String indexPrefix;

    public void recordStep(RagAgentState state, RagAgentStepTrace step) {
        if (!enabled || state == null || step == null) {
            return;
        }

        Map<String, Object> doc = baseDoc("rag_agent_step", state);
        doc.put("step_name", step.stepName());
        doc.put("decision", step.decision());
        doc.put("success", step.success());
        doc.put("cost_ms", step.costMs());
        doc.put("summary", step.summary());
        write(doc);
    }

    public void recordCompleted(RagAgentState state) {
        if (!enabled || state == null || state.plan() == null) {
            return;
        }

        EvidenceResult evidence = state.evidenceResult();
        Map<String, Object> doc = baseDoc("rag_agent_completed", state);
        doc.put("question_type", state.plan().questionType().name());
        doc.put("retrieval_mode", state.plan().retrievalMode().name());
        doc.put("top_k", state.currentTopK());
        doc.put("retry_count", state.retryCount());
        doc.put("evidence_sufficient", evidence != null && evidence.sufficient());
        doc.put("evidence_score", evidence == null ? null : evidence.score());
        doc.put("evidence_action", evidence == null ? null : evidence.suggestedAction().name());
        doc.put("answer_doc_count", state.answerDocs().size());
        doc.put("step_count", state.steps().size());
        write(doc);
    }

    public void recordFailed(RagAgentState state, Exception exception) {
        if (!enabled || state == null) {
            return;
        }

        Map<String, Object> doc = baseDoc("rag_agent_failed", state);
        if (state.plan() != null) {
            doc.put("question_type", state.plan().questionType().name());
            doc.put("retrieval_mode", state.plan().retrievalMode().name());
        }
        doc.put("top_k", state.currentTopK());
        doc.put("retry_count", state.retryCount());
        doc.put("step_count", state.steps().size());
        doc.put("error_type", exception == null ? null : exception.getClass().getSimpleName());
        doc.put("error_message", exception == null ? null : exception.getMessage());
        write(doc);
    }

    private Map<String, Object> baseDoc(String eventType, RagAgentState state) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("@timestamp", Instant.now().toString());
        doc.put("event_type", eventType);
        doc.put("service", "zhiguang-be");
        doc.put("trace_id", state.traceId());
        doc.put("original_question", state.originalQuestion());
        doc.put("standalone_question", state.standaloneQuestion());
        return doc;
    }

    private void write(Map<String, Object> doc) {
        try {
            elasticsearchClient.index(i -> i
                    .index(indexName())
                    .document(doc)
            );
        } catch (Exception e) {
            log.warn("Agent observation write skipped: {}", e.getMessage());
        }
    }

    private String indexName() {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        return indexPrefix + "-" + day;
    }
}
