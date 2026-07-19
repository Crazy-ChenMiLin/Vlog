package com.tongji.llm.memoryService;

import com.tongji.common.id.IdService;
import com.tongji.llm.chat.model.RagChatRole;
import com.tongji.llm.chat.model.RagChatScope;
import com.tongji.llm.memoryService.mapper.RagConversationMapper;
import com.tongji.llm.memoryService.mapper.RagMessageMapper;
import com.tongji.llm.memoryService.model.RagConversation;
import com.tongji.llm.memoryService.model.RagMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RagConversationMemoryService {
    public static final int DEFAULT_HISTORY_LIMIT = 6;

    private final IdService idService;
    private final RagConversationMapper conversationMapper;
    private final RagMessageMapper messageMapper;

    public RagConversation createConversation(long userId, String scope, Long postId) {
        validateScopeAndPost(scope, postId);
        long id = idService.nextId();
        conversationMapper.insert(id, userId, scope, postId, defaultTitle(scope));
        RagConversation conversation = conversationMapper.findById(id);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "会话创建失败");
        }
        return conversation;
    }

    public RagConversation resolveConversation(Long conversationId, long userId, String scope, Long postId) {
        validateScopeAndPost(scope, postId);
        if (conversationId == null) {
            return createConversation(userId, scope, postId);
        }

        RagConversation conversation = conversationMapper.findById(conversationId);
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        if (!Objects.equals(conversation.getUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
        if (!Objects.equals(conversation.getScope(), scope)
                || !Objects.equals(conversation.getPostId(), postId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话范围不匹配");
        }
        return conversation;
    }

    public List<RagMessage> loadRecentMessages(long userId, long conversationId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, DEFAULT_HISTORY_LIMIT));
        List<RagMessage> messages = messageMapper.listRecent(conversationId, userId, safeLimit);
        Collections.reverse(messages);
        return messages;
    }

    public void appendMessage(long userId, long conversationId, RagChatRole role, String content) {
        if (!StringUtils.hasText(content)) {
            return;
        }
        messageMapper.insert(idService.nextId(), conversationId, userId, role.value(), content);
        conversationMapper.touch(conversationId, userId);
    }

    private void validateScopeAndPost(String scope, Long postId) {
        if (!RagChatScope.isSupported(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的会话范围");
        }
        if (RagChatScope.GLOBAL.is(scope) && postId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "全库会话不能绑定文章");
        }
        if (RagChatScope.POST.is(scope) && postId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单篇文章会话缺少 postId");
        }
    }

    private String defaultTitle(String scope) {
        return RagChatScope.fromValue(scope)
                .map(RagChatScope::defaultTitle)
                .orElse("问答");
    }
}
