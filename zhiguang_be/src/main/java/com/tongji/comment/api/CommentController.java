package com.tongji.comment.api;

import com.tongji.auth.token.JwtService;
import com.tongji.common.exception.BusinessException;
import com.tongji.common.exception.ErrorCode;
import com.tongji.comment.api.dto.CommentResponse;
import com.tongji.comment.api.dto.CreateCommentRequest;
import com.tongji.comment.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/comments")
@Validated
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;
    private final JwtService jwtService;

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody CreateCommentRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        long userId = jwtService.extractUserId(jwt);
        long postId;
        try {
            postId = Long.parseLong(request.postId());
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "postId 非法");
        }
        commentService.create(postId, userId, request.content());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<CommentResponse> list(@RequestParam("postId") long postId,
                                @RequestParam(value = "page", defaultValue = "1") int page,
                                @RequestParam(value = "size", defaultValue = "20") int size) {
        return commentService.listByPostId(postId, page, size);
    }
}
