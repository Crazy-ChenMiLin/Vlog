package com.tongji.benchmark.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.benchmark.model.dto.BenchmarkCaseDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkCaseServiceTest {

    @Test
    void loadsGoldCaseFromThePackagedSingleSourceDataset() {
        BenchmarkCaseService service = new BenchmarkCaseService(new ObjectMapper());

        BenchmarkCaseDTO benchmarkCase = service.getRequiredCase("gold-003");

        assertThat(benchmarkCase.question()).contains("HyDE");
        assertThat(benchmarkCase.expectedChunkIds()).containsExactly("335239822308413440#1");
        assertThat(benchmarkCase.scenarioTags()).contains("RAG");
    }
}
