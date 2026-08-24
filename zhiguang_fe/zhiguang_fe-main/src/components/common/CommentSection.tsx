import { useCallback, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "@/context/AuthContext";
import { commentService } from "@/services/commentService";
import type { CommentItem } from "@/types/comment";
import styles from "./CommentSection.module.css";

type CommentSectionProps = {
  postId: string;
};

const PAGE_SIZE = 20;

const CommentSection = ({ postId }: CommentSectionProps) => {
  const { tokens, user } = useAuth();
  const navigate = useNavigate();
  const [comments, setComments] = useState<CommentItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const listEndRef = useRef<HTMLDivElement | null>(null);

  const loadPage = useCallback(async (p: number, append: boolean) => {
    setLoading(true);
    setError(null);
    try {
      const list = await commentService.list(postId, p, PAGE_SIZE);
      if (append) {
        setComments(prev => [...prev, ...list]);
      } else {
        setComments(list);
      }
      setHasMore(list.length === PAGE_SIZE);
      setPage(p);
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载评论失败");
    } finally {
      setLoading(false);
    }
  }, [postId]);

  useEffect(() => {
    loadPage(1, false);
  }, [loadPage]);

  const handleSubmit = async () => {
    const text = input.trim();
    if (!text) return;
    if (!tokens?.accessToken) {
      navigate("/login", { state: { from: window.location.pathname } });
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await commentService.create({ postId, content: text }, tokens.accessToken);
      setInput("");
      await loadPage(1, false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "发表评论失败");
    } finally {
      setSubmitting(false);
    }
  };

  const handleLoadMore = () => {
    if (!loading && hasMore) {
      loadPage(page + 1, true);
    }
  };

  const formatTime = (iso: string) => {
    try {
      return new Date(iso).toLocaleString("zh-CN", {
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      });
    } catch {
      return iso;
    }
  };

  return (
    <section className={styles.section}>
      <h3 className={styles.title}>评论 ({comments.length})</h3>

      <div className={styles.inputBox}>
        <textarea
          className={styles.textarea}
          placeholder={tokens?.accessToken ? "写下你的评论…" : "请先登录后评论"}
          value={input}
          onChange={e => setInput(e.target.value)}
          maxLength={1024}
          rows={3}
          disabled={submitting}
        />
        <div className={styles.inputFooter}>
          <span className={styles.charCount}>{input.length}/1024</span>
          <button
            type="button"
            className={styles.submitBtn}
            onClick={handleSubmit}
            disabled={submitting || !input.trim()}
          >
            {submitting ? "发送中…" : "发表评论"}
          </button>
        </div>
      </div>

      {error ? <div className={styles.error}>{error}</div> : null}

      <div className={styles.list} ref={listEndRef}>
        {comments.length === 0 && !loading ? (
          <div className={styles.empty}>还没有评论，来说点什么吧</div>
        ) : null}
        {comments.map(c => (
          <div key={c.id} className={styles.comment}>
            <div className={styles.avatarWrap}>
              {c.avatar ? (
                <img className={styles.avatar} src={c.avatar} alt={c.nickname} />
              ) : (
                <div className={styles.avatarFallback}>
                  {c.nickname?.charAt(0).toUpperCase() || "?"}
                </div>
              )}
            </div>
            <div className={styles.commentBody}>
              <div className={styles.commentMeta}>
                <span className={styles.nickname}>{c.nickname}</span>
                {user && user.id === c.userId ? (
                  <span className={styles.selfBadge}>我</span>
                ) : null}
                <span className={styles.time}>{formatTime(c.createTime)}</span>
              </div>
              <div className={styles.commentContent}>{c.content}</div>
            </div>
          </div>
        ))}
      </div>

      {hasMore ? (
        <button
          type="button"
          className={styles.loadMore}
          onClick={handleLoadMore}
          disabled={loading}
        >
          {loading ? "加载中…" : "加载更多"}
        </button>
      ) : null}
    </section>
  );
};

export default CommentSection;
