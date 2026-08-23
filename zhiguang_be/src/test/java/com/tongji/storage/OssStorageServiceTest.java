package com.tongji.storage;

import com.tongji.storage.config.OssProperties;
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
}
