package com.tongji.benchmark.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.benchmark.model.dto.BenchmarkCaseDTO;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 读取版本化 Gold 数据集，并按 caseId 提供题目、期望证据 chunk 与评测上下文。
 */
@Service
public class BenchmarkCaseService {
    private static final Map<String, String> DATASET_RESOURCES = Map.of(
            "t2-automotive-maintenance-v1", "benchmark/automotive-maintenance/gold-v1.json",
            "t2-history-culture-v1", "benchmark/history-culture/gold-v1.json",
            "t2-computer-operations-v1", "benchmark/computer-operations/gold-v1.json",
            "t2-daily-home-v1", "benchmark/daily-home/gold-v1.json",
            "t2-education-development-v1", "benchmark/education-development/gold-v1.json"
    );

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, BenchmarkCaseDTO>> casesByDataset = new ConcurrentHashMap<>();

    public BenchmarkCaseService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BenchmarkCaseDTO getRequiredCase(String caseId) {
        return getRequiredCase("t2-automotive-maintenance-v1", caseId);
    }

    public BenchmarkCaseDTO getRequiredCase(String datasetVersion, String caseId) {
        BenchmarkCaseDTO benchmarkCase = cases(datasetVersion).get(caseId);
        if (benchmarkCase == null) {
            throw new IllegalArgumentException(
                    "Unknown benchmark caseId " + caseId + " in dataset " + datasetVersion
            );
        }
        return benchmarkCase;
    }

    private Map<String, BenchmarkCaseDTO> cases(String datasetVersion) {
        String resourcePath = DATASET_RESOURCES.get(datasetVersion);
        if (resourcePath == null) {
            throw new IllegalArgumentException("Unknown benchmark dataset: " + datasetVersion);
        }
        return casesByDataset.computeIfAbsent(datasetVersion, ignored -> loadCases(resourcePath));
    }

    private Map<String, BenchmarkCaseDTO> loadCases(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream inputStream = resource.getInputStream()) {
            List<BenchmarkCaseDTO> cases = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<BenchmarkCaseDTO>>() { }
            );
            return cases.stream().collect(Collectors.toUnmodifiableMap(
                    BenchmarkCaseDTO::caseId,
                    Function.identity()
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load benchmark dataset: " + resourcePath, exception);
        }
    }
}
