package com.tongji.llm.external;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GithubOfficialGoProviderTest {

    private final GithubOfficialGoProvider provider = new GithubOfficialGoProvider(
            RestClient.builder(), new GithubOfficialGoProperties());

    @Test
    void recognizesGoQuestionsAndRoutesConcurrencyToOfficialLinks() {
        assertThat(provider.supports("Go 语言里的 goroutine 和 channel 有什么区别？")).isTrue();

        List<ExternalKnowledgeResource> resources = provider.findResources("Go 的 goroutine 和 channel 怎么协作？", 3);

        assertThat(resources).isNotEmpty();
        assertThat(resources).allSatisfy(resource -> {
            assertThat(resource.repository()).isEqualTo("golang/go");
            assertThat(resource.url()).startsWith("https://github.com/golang/go/blob/master/");
        });
        assertThat(resources).anySatisfy(resource -> assertThat(resource.path()).isEqualTo("src/runtime/proc.go"));
    }

    @Test
    void ignoresQuestionsOutsideGoScope() {
        assertThat(provider.supports("Spring Boot 事务传播是什么？")).isFalse();
        assertThat(provider.findResources("Spring Boot 事务传播是什么？", 3)).isEmpty();
    }
}
