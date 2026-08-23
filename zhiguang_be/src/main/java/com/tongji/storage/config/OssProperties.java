package com.tongji.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "oss")
public class OssProperties {
    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucket;
    private String publicDomain; // 可选：如自定义 CDN 域名
    /** Browser-reachable S3 endpoint used only when signing direct uploads. */
    private String presignEndpoint;
    /** MinIO default region; setting this avoids a region-discovery request through the gateway. */
    private String region = "us-east-1";
    private String folder = "avatars"; // 默认上传目录
}
