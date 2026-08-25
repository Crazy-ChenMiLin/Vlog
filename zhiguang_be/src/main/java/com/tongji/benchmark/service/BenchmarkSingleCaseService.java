package com.tongji.benchmark.service;

import com.tongji.benchmark.assembler.FullTranscriptAssembler;
import com.tongji.benchmark.model.dto.BenchmarkCaseDTO;
import com.tongji.benchmark.model.dto.BenchmarkEvaluationContextDTO;
import com.tongji.llm.chat.RagQueryService;
import com.tongji.llm.observability.model.dto.transcript.RagTranscriptDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 编排一道 Gold 题的一次完整内部评测。
 * <p>核心设计：复用正常 RAG 链路，不另写一套检索逻辑。
 * 普通用户提问和评测跑测试集，走的是同一个 RAG 执行方法，
 * 区别只在于：
 * - 普通用户：问题来自前端，返回流式答案，后台异步记录 Transcript
 * - 评测用户：问题来自 Gold 测试集，返回完整 Transcript（带评测标注）
 */
@Service
@RequiredArgsConstructor
public class BenchmarkSingleCaseService {
    // 依赖1：根据 caseId 找 Gold 题（题目、标准答案 chunk）
    private final BenchmarkCaseService benchmarkCaseService;
    // 依赖2：正常 RAG 问答服务（复用，不另写一套）
    private final RagQueryService ragQueryService;
    /**
     * 执行一道 Gold 题的一次完整评测。
     * @param context 评测上下文（runId、caseId、datasetVersion）
     * @param topK    召回多少条文档
     * @return 带 Gold 评测标注的完整 Transcript（evaluation、goldHit、goldRanks 都已填好）
     */
    public Mono<RagTranscriptDTO> execute(BenchmarkEvaluationContextDTO context, int topK) {
        // 第一步：根据 caseId 从 Gold 数据集找到这道题
        // 拿到：题目内容（question）、标准答案 chunk（expectedChunkIds）、场景标签
        BenchmarkCaseDTO benchmarkCase = benchmarkCaseService.getRequiredCase(
                context.datasetVersion(),
                context.caseId()
        );

        // 第二步：调用正常 RAG 链路，执行一次真实问答
        // 注意：这里调用的 generateGlobalTranscript 就是普通用户提问走的同一个方法
        // 传入：Gold题的问题、topK、runId
        // 返回：基础版 Transcript（五阶段候选结果、最终答案、traceId，evaluation=null）
        return ragQueryService.generateGlobalTranscript(
                        benchmarkCase.question(),
                        topK,
                        context.runId()
                )
                // 第三步：在基础版 Transcript 上附加 Gold 评测标注
                // 干的事：
                // 1. 填 evaluation 字段（runId、caseId、expectedChunkIds、datasetVersion）
                // 2. 调用 StageHitEvaluator 给每个阶段算 goldHit（有没有命中标准答案）
                // 3. 填 goldRanks（命中的话，正确文档排第几名）
                // 返回：评测版 Transcript（和基础版是同一个对象，只是多了评测标注）
                .map(transcript -> FullTranscriptAssembler.attachEvaluation(
                        transcript,
                        benchmarkCase,
                        context.runId(),
                        context.datasetVersion()
                ));
    }
}
