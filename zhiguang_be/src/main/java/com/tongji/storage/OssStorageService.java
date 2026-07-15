package com.tongji.storage;

import com.tongji.storage.config.OssProperties;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OssStorageService {

    private final OssProperties props;

    public String uploadAvatar(long userId, MultipartFile file) {
        ensureConfigured();

        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf('.'));
        }
        String objectKey = props.getFolder() + "/" + userId + "-" + Instant.now().toEpochMilli() + ext;

        MinioClient client = buildClient();
        try {
            ensureBucketExists(client);
            client.putObject(PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .stream(file.getInputStream(), file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "头像文件读取失败");
        }

        return publicUrl(objectKey);
    }

    public String publicUrl(String objectKey) {
        String base = StringUtils.hasText(props.getPublicDomain()) ? props.getPublicDomain() : props.getEndpoint();
        base = trimTrailingSlash(base);
        if (!StringUtils.hasText(base)) {
            return objectKey;
        }
        return base + publicObjectPath(base, objectKey);
    }

    private String publicObjectPath(String base, String objectKey) {
        return hasPath(base)
                ? "/" + objectKey
                : "/" + props.getBucket() + "/" + objectKey;
    }

    private boolean hasPath(String base) {
        try {
            String path = URI.create(base).getPath();
            return path != null && !path.isBlank() && !"/".equals(path);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * 生成用于直传的 PUT 预签名 URL。
     * 客户端必须在上传时设置与签名一致的 Content-Type。
     *
     * @param objectKey 目标对象键
     * @param contentType 上传内容类型（如 text/markdown, image/png）
     * @param expiresInSeconds 有效期秒数（建议 300-900）
     * @return 可直接用于 PUT 上传的预签名 URL
     */
    public String generatePresignedPutUrl(String objectKey, String contentType, int expiresInSeconds) {
        ensureConfigured();
        MinioClient client = buildClient();
        try {
            ensureBucketExists(client);
            Map<String, String> extraHeaders = (contentType == null || contentType.isBlank())
                    ? Map.of()
                    : Map.of("Content-Type", contentType);
            return client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .expiry((int) Math.max(1, expiresInSeconds))
                            .extraHeaders(extraHeaders)
                            .build()
            );
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储预签名失败");
        }
    }

    private void ensureConfigured() {
        if (!StringUtils.hasText(props.getEndpoint())
                || !StringUtils.hasText(props.getAccessKeyId())
                || !StringUtils.hasText(props.getAccessKeySecret())
                || !StringUtils.hasText(props.getBucket())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储未配置");
        }
    }

    private MinioClient buildClient() {
        return MinioClient.builder()
                .endpoint(trimTrailingSlash(props.getEndpoint()))
                .credentials(props.getAccessKeyId(), props.getAccessKeySecret())
                .build();
    }

    private void ensureBucketExists(MinioClient client) {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder()
                    .bucket(props.getBucket())
                    .build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder()
                        .bucket(props.getBucket())
                        .build());
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "对象存储桶不可用");
        }
    }

    private String trimTrailingSlash(String endpoint) {
        return endpoint == null ? null : endpoint.replaceAll("/$", "");
    }
}
