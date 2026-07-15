package com.tongji.llm.rag.mapper;

import com.tongji.llm.rag.model.RagMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RagMessageMapper {
    int insert(@Param("id") Long id,
               @Param("conversationId") Long conversationId,
               @Param("userId") Long userId,
               @Param("role") String role,
               @Param("content") String content);

    List<RagMessage> listRecent(@Param("conversationId") Long conversationId,
                                @Param("userId") Long userId,
                                @Param("limit") int limit);
}
