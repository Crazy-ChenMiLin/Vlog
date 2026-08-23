package com.tongji.storage;

import com.tongji.storage.config.OssProperties;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OssStorageServiceTest {

    @Test
    void usesBrowserReachableEndpointForPresignedUploads() {
        OssProperties properties = new OssProperties();
        properties.setEndpoint("http://100.83.242.114:9000");
        properties.setPresignEndpoint("https://47.108.66.230/");

        OssStorageService service = new OssStorageService(properties);

        assertThat(service.resolvePresignEndpoint()).isEqualTo("https://47.108.66.230");
    }

    @Test
    void canCreateAHostedPresignedUrlWithoutContactingThePublicGateway() throws Exception {
        OssProperties properties = new OssProperties();
        properties.setEndpoint("http://100.83.242.114:9000");
        properties.setPresignEndpoint("https://47.108.66.230");
        properties.setRegion("us-east-1");
        properties.setAccessKeyId("test-access-key");
        properties.setAccessKeySecret("test-access-secret");

        OssStorageService service = new OssStorageService(properties);
        java.lang.reflect.Method factory = OssStorageService.class.getDeclaredMethod("buildPresignClient");
        factory.setAccessible(true);
        MinioClient client = (MinioClient) factory.invoke(service);

        String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket("zhiguang")
                .object("posts/1/content.md")
                .expiry(60)
                .build());

        assertThat(url).startsWith("https://47.108.66.230/zhiguang/posts/1/content.md?");
    }
}
