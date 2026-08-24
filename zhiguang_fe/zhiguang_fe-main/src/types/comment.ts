export type CommentItem = {
  id: number;
  postId: number;
  userId: number;
  content: string;
  nickname: string;
  avatar?: string;
  createTime: string;
};

export type CreateCommentRequest = {
  // Snowflake IDs exceed JavaScript's safe integer range; keep them as strings.
  postId: string;
  content: string;
};
