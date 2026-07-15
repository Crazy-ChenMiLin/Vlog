package com.tongji.llm.rag.mapper;

import com.tongji.llm.rag.model.RagConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RagConversationMapper {
    int insert(@Param("id") Long id,
               @Param("userId") Long userId,
               @Param("scope") String scope,
               @Param("postId") Long postId,
               @Param("title") String title);

    RagConversation findById(@Param("id") Long id);

    int touch(@Param("id") Long id, @Param("userId") Long userId);
}
