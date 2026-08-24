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

        assertThat(benchmarkCase.question()).isEqualTo("发动机动力不足时应检查哪些系统？");
        assertThat(benchmarkCase.expectedChunkIds()).contains("456537");
        assertThat(benchmarkCase.scenarioTags()).contains("汽车维护与故障诊断");
    }
}
