package com.tongji.llm.searchService;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tongji.common.exception.BusinessException;
import com.tongji.config.EsProperties;
import com.tongji.knowpost.mapper.KnowPostMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RagIndexServiceTest {

    @Mock
    private VectorStore vectorStore;
    @Mock
    private KnowPostMapper knowPostMapper;
    @Mock
    private ElasticsearchClient elasticsearchClient;

    @Test
    void deletePostFailsFastWhenIndexNameIsMissing() {
        EsProperties properties = new EsProperties();
        RagIndexService service = new RagIndexService(
                vectorStore, knowPostMapper, elasticsearchClient, properties);

        assertThatThrownBy(() -> service.deletePost(123L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("未配置向量索引名称");

        verifyNoInteractions(elasticsearchClient);
    }
}
