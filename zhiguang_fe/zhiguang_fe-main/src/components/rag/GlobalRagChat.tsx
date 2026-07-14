import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ArrowRightIcon, SparkIcon } from "@/components/icons/Icon";
import { useRagStream } from "@/features/rag/useRagStream";
import { resolveApiUrl } from "@/services/apiClient";
import styles from "./GlobalRagChat.module.css";

const TOP_K_OPTIONS = [3, 5, 8, 12];

const GlobalRagChat = () => {
  const [question, setQuestion] = useState("");
  const [submittedQuestion, setSubmittedQuestion] = useState("");
  const [topK, setTopK] = useState(5);
  const answerViewportRef = useRef<HTMLDivElement | null>(null);
  const { answer, loading, error, start, stop } = useRagStream();

  useEffect(() => {
    const viewport = answerViewportRef.current;
    if (viewport && answer) {
      viewport.scrollTop = viewport.scrollHeight;
    }
  }, [answer]);

  const ask = () => {
    const normalizedQuestion = question.trim();
    if (!normalizedQuestion || loading) return;

    setSubmittedQuestion(normalizedQuestion);
    const query = new URLSearchParams({
      question: normalizedQuestion,
      topK: String(topK)
    });
    start(resolveApiUrl(`/api/v1/knowposts/qa/stream?${query.toString()}`));
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    ask();
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      ask();
    }
  };

  return (
    <section className={styles.panel} aria-labelledby="global-rag-title">
      <header className={styles.header}>
        <div className={styles.titleGroup}>
          <span className={styles.spark} aria-hidden="true">
            <SparkIcon width={20} height={20} />
          </span>
          <div>
            <h2 id="global-rag-title" className={styles.title}>问知光</h2>
            <span className={styles.scope}>全库</span>
          </div>
        </div>

        <div className={styles.headerControls}>
          {loading ? <span className={styles.status}><i />正在生成</span> : null}
          <label className={styles.topKControl}>
            <span>Top K</span>
            <select value={topK} onChange={(event) => setTopK(Number(event.target.value))}>
              {TOP_K_OPTIONS.map((value) => (
                <option key={value} value={value}>{value}</option>
              ))}
            </select>
          </label>
        </div>
      </header>

      <div ref={answerViewportRef} className={styles.answerViewport} aria-live="polite">
        {!submittedQuestion ? (
          <div className={styles.emptyState}>
            <SparkIcon width={26} height={26} aria-hidden="true" />
            <span>等待你的问题</span>
          </div>
        ) : (
          <>
            <div className={styles.questionBubble}>{submittedQuestion}</div>
            <div className={styles.answerBlock}>
              {answer ? (
                <ReactMarkdown
                  remarkPlugins={[remarkGfm]}
                  components={{
                    a: ({ node, ...props }) => <a {...props} target="_blank" rel="noreferrer" />,
                    img: ({ node, ...props }) => <img {...props} alt={props.alt ?? ""} />
                  }}
                >
                  {answer}
                </ReactMarkdown>
              ) : loading ? (
                <div className={styles.thinking}>
                  <span />
                  <span />
                  <span />
                </div>
              ) : null}
            </div>
          </>
        )}
        {error ? <div className={styles.error} role="alert">{error}</div> : null}
      </div>

      <form className={styles.composer} onSubmit={handleSubmit}>
        <textarea
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          onKeyDown={handleKeyDown}
          maxLength={500}
          rows={2}
          placeholder="问问公开知文，例如：map、flatMap 和 collect 有什么区别？"
          aria-label="输入知识库问题"
        />
        <div className={styles.actions}>
          <button
            type="button"
            className={styles.stopButton}
            onClick={stop}
            disabled={!loading}
            title="停止生成"
          >
            <span aria-hidden="true" />
            停止
          </button>
          <button
            type="submit"
            className={styles.sendButton}
            disabled={loading || !question.trim()}
          >
            发送
            <ArrowRightIcon width={18} height={18} aria-hidden="true" />
          </button>
        </div>
      </form>
    </section>
  );
};

export default GlobalRagChat;
