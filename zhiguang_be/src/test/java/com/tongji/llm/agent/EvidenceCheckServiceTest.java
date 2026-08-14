package com.tongji.llm.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.llm.agent.state.EvidenceAction;
import com.tongji.llm.agent.state.EvidenceResult;
import com.tongji.llm.config.RagLlmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceCheckServiceTest {

    private final EvidenceCheckService service = new EvidenceCheckService(null, new ObjectMapper(), ragLlmProperties());

    @Test
    void parseEvidenceJson() throws Exception {
        EvidenceResult result = service.parse("""
                {
                  "sufficient": false,
                  "score": 0.42,
                  "reason": "证据不足",
                  "suggestedAction": "EXPAND_TOP_K"
                }
                """);

        assertThat(result.sufficient()).isFalse();
        assertThat(result.score()).isEqualTo(0.42);
        assertThat(result.suggestedAction()).isEqualTo(EvidenceAction.EXPAND_TOP_K);
    }

    @Test
    void badEvidenceJsonThrowsSoCallerCanFallback() {
        assertThatThrownBy(() -> service.parse("not-json"))
                .isInstanceOf(Exception.class);
    }

    private RagLlmProperties ragLlmProperties() {
        RagLlmProperties properties = new RagLlmProperties();
        properties.setDefaultModel("test-model");
        return properties;
    }
}
