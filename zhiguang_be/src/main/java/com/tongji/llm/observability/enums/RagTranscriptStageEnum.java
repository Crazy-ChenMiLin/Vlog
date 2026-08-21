package com.tongji.llm.observability.enums;

/**
 * Transcript 统一使用的五个检索阶段。
 */
public enum RagTranscriptStageEnum {
    ORIGINAL,
    HYDE,
    KEYWORD,
    FUSED,
    RERANKED
}
