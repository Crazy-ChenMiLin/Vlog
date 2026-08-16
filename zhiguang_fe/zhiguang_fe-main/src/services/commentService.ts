import { apiFetch } from "./apiClient";
import type { CommentItem, CreateCommentRequest } from "@/types/comment";

const PREFIX = "/api/v1/comments";

export const commentService = {
  list: (postId: string, page = 1, size = 20) =>
    apiFetch<CommentItem[]>(`${PREFIX}?postId=${postId}&page=${page}&size=${size}`),

  create: (payload: CreateCommentRequest, accessToken: string) =>
    apiFetch<void>(`${PREFIX}`, {
      method: "POST",
      body: payload,
      accessToken,
    }),
};
