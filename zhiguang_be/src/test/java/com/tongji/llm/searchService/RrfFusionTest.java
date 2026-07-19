package com.tongji.llm.searchService;

import com.tongji.llm.enhanceService.RrfFusionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    private final RrfFusionService fusion = new RrfFusionService();

    @Test
    void documentFoundByBothRoutesRanksAheadOfSingleRouteDocuments() {
        Document originalOnly = document("1#0", "原问题命中的切片");
        Document shared = document("1#1", "两路共同命中的切片");
        Document hydeOnly = document("1#2", "HyDE 命中的切片");

        List<Document> result = fusion.fuse(
                List.of(
                        List.of(originalOnly, shared),
                        List.of(shared, hydeOnly)
                ),
                3
        );

        assertThat(result)
                .extracting(document -> document.getMetadata().get("chunkId"))
                .containsExactly("1#1", "1#0", "1#2");
    }

    @Test
    void duplicateChunkIsReturnedOnlyOnce() {
        Document firstCopy = document("1#0", "第一次召回");
        Document secondCopy = document("1#0", "第二次召回");

        List<Document> result = fusion.fuse(
                List.of(List.of(firstCopy), List.of(secondCopy)),
                5
        );

        assertThat(result).hasSize(1);
    }

    private Document document(String chunkId, String text) {
        return new Document(text, Map.of("postId", "1", "chunkId", chunkId));
    }
}
