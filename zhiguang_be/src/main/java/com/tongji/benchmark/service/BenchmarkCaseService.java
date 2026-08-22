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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 读取版本化 Gold 数据集，并按 caseId 提供题目、期望证据 chunk 与评测上下文。
 */
@Service
public class BenchmarkCaseService {
    private static final String DATASET_RESOURCE = "benchmark/gold-dataset-v1.json";

    private final ObjectMapper objectMapper;
    private volatile Map<String, BenchmarkCaseDTO> casesById;

    public BenchmarkCaseService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public BenchmarkCaseDTO getRequiredCase(String caseId) {
        BenchmarkCaseDTO benchmarkCase = cases().get(caseId);
        if (benchmarkCase == null) {
            throw new IllegalArgumentException("Unknown benchmark caseId: " + caseId);
        }
        return benchmarkCase;
    }

    private Map<String, BenchmarkCaseDTO> cases() {
        Map<String, BenchmarkCaseDTO> cached = casesById;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (casesById == null) {
                casesById = loadCases();
            }
            return casesById;
        }
    }

    private Map<String, BenchmarkCaseDTO> loadCases() {
        ClassPathResource resource = new ClassPathResource(DATASET_RESOURCE);
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
            throw new IllegalStateException("Cannot load benchmark dataset: " + DATASET_RESOURCE, exception);
        }
    }
}
