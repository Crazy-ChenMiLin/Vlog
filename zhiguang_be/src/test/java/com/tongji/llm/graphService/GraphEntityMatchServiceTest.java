package com.tongji.llm.graphService;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GraphEntityMatchServiceTest {

    private final GraphEntityMatchService service = new GraphEntityMatchService();

    @Test
    void matchesCacheRelationEntitiesByAliases() {
        var result = service.match("缓存命中和缓存击穿是什么关系，互斥锁有什么作用？");

        assertThat(result)
                .extracting(entity -> entity.name())
                .contains("缓存命中", "缓存击穿", "互斥锁");
    }

    @Test
    void emptyQuestionReturnsNoEntities() {
        assertThat(service.match(" ")).isEmpty();
    }
}
