package com.tongji.benchmark.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.benchmark.model.dto.BenchmarkCaseDTO;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkCaseServiceTest {

    @Test
    void loadsGoldCaseFromThePackagedSingleSourceDataset() {
        BenchmarkCaseService service = new BenchmarkCaseService(new ObjectMapper());

        BenchmarkCaseDTO benchmarkCase = service.getRequiredCase("gold-003");

        assertThat(benchmarkCase.question()).isEqualTo("发动机动力不足时应检查哪些系统？");
        assertThat(benchmarkCase.expectedChunkIds()).contains("456537");
        assertThat(benchmarkCase.scenarioTags()).contains("汽车维护与故障诊断");
    }

    @Test
    void loadsTheSameCaseIdFromDifferentWhitelistedScenarioDatasets() {
        BenchmarkCaseService service = new BenchmarkCaseService(new ObjectMapper());

        assertThat(service.getRequiredCase("t2-history-culture-v1", "gold-001").question())
                .contains("三国演义");
        assertThat(service.getRequiredCase("t2-computer-operations-v1", "gold-001").question())
                .contains("MySQL");
        assertThat(service.getRequiredCase("t2-daily-home-v1", "gold-001").question())
                .contains("四川");
        assertThat(service.getRequiredCase("t2-education-development-v1", "gold-001").question())
                .contains("士官学校");
        assertThatThrownBy(() -> service.getRequiredCase("unreviewed-dataset", "gold-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown benchmark dataset");
    }
}
