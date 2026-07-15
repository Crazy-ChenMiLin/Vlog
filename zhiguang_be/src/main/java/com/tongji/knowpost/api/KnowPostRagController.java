package com.tongji.knowpost.api;

import com.tongji.llm.rag.RagIndexService;
import com.tongji.llm.rag.RagQueryService;
import com.tongji.llm.rag.RagDebugService;
import com.tongji.llm.DTO.RagRetrievalDebugDTO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/v1/knowposts")
@Validated
@RequiredArgsConstructor
public class KnowPostRagController {

    private final RagIndexService indexService;
    private final RagQueryService ragQueryService;
    private final RagDebugService ragDebugService;

    /**
     * 单篇知文 RAG 问答，保持默认 SSE message 以兼容现有前端。
     */
    @GetMapping(value = "/{id}/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> qaStream(
            @PathVariable("id") @Positive long id,
            @RequestParam("question") @NotBlank @Size(max = 500) String question,
            @RequestParam(value = "topK", defaultValue = "5") @Min(1) @Max(20) int topK) {
        return ragQueryService.streamPostAnswerFlux(id, question.trim(), topK);
    }

    /**
     * 全知识库 RAG 问答，不限制具体知文。
     */
    @GetMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> globalQaStream(
            @RequestParam("question") @NotBlank @Size(max = 500) String question,
            @RequestParam(value = "topK", defaultValue = "5") @Min(1) @Max(20) int topK) {
        return ragQueryService.streamGlobalAnswerFlux(question.trim(), topK);
    }

    @GetMapping("/{id}/qa/debug")
    public RagRetrievalDebugDTO qaDebug(
            @PathVariable("id") @Positive long id,
            @RequestParam("question") @NotBlank @Size(max = 500) String question,
            @RequestParam(value = "topK", defaultValue = "5") @Min(1) @Max(20) int topK) {
        return ragDebugService.debugPostRetrieval(id, question.trim(), topK);
    }

    @GetMapping("/qa/debug")
    public RagRetrievalDebugDTO globalQaDebug(
            @RequestParam("question") @NotBlank @Size(max = 500) String question,
            @RequestParam(value = "topK", defaultValue = "5") @Min(1) @Max(20) int topK) {
        return ragDebugService.debugGlobalRetrieval(question.trim(), topK);
    }

    /**
     * 手动触发单篇索引重建（返回重建的切片数）。
     */
    @PostMapping("/{id}/rag/reindex")
    public int reindex(@PathVariable("id") @Positive long id) {
        return indexService.reindexSinglePost(id);
    }
}
