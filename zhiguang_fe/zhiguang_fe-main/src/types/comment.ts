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
  postId: number;
  content: string;
};
