package com.tongji.comment.mapper;

import com.tongji.comment.api.dto.CommentResponse;
import com.tongji.comment.model.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommentMapper {
    void insert(Comment comment);

    List<CommentResponse> listByPostId(@Param("postId") long postId,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);
}
