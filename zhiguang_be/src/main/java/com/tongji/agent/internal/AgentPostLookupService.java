package com.tongji.agent.internal;

import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPostDetailRow;
import com.tongji.storage.OssStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Read-only post lookup for the Agent runtime.
 *
 * <p>The post body is not stored in MySQL: it lives in object storage keyed by
 * {@code content_object_key}, so it is fetched on demand here. This is the
 * "Context on Demand" principle from v5 §14 — never bulk-load every post.</p>
 */
@Service
@RequiredArgsConstructor
public class AgentPostLookupService {

    private final KnowPostMapper knowPostMapper;
    private final OssStorageService ossStorageService;

    public AgentPostView getForAgent(long id) {
        KnowPostDetailRow row = knowPostMapper.findDetailById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "帖子不存在: " + id);
        }

        String content = "";
        if (StringUtils.hasText(row.getContentObjectKey())) {
            try {
                content = ossStorageService.readObject(row.getContentObjectKey());
            } catch (BusinessException e) {
                // Body unavailable: surface the reason rather than silently
                // returning an empty post, so failures stay observable (v5 §19.5).
                content = "[正文读取失败: " + e.getMessage() + "]";
            }
        } else if (StringUtils.hasText(row.getContentUrl())) {
            content = "[正文仅可通过 URL 访问: " + row.getContentUrl() + "]";
        }

        return new AgentPostView(
                row.getId(),
                row.getTitle(),
                content,
                row.getAuthorNickname(),
                row.getTags()
        );
    }
}
