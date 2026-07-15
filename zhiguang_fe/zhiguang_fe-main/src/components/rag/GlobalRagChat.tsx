import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ArrowRightIcon, ChatBubbleIcon, CloseIcon, MinimizeIcon, SparkIcon } from "@/components/icons/Icon";
import { useAuth } from "@/context/AuthContext";
import { useRagStream } from "@/features/rag/useRagStream";
import { resolveApiUrl } from "@/services/apiClient";
import styles from "./GlobalRagChat.module.css";

const TOP_K_OPTIONS = [3, 5, 8, 12];

const GlobalRagChat = () => {
  const [expanded, setExpanded] = useState(false);
  const [question, setQuestion] = useState("");
  const [submittedQuestion, setSubmittedQuestion] = useState("");
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const [topK, setTopK] = useState(5);
  const answerViewportRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const { answer, loading, error, start, stop } = useRagStream();
  const { tokens } = useAuth();

  useEffect(() => {
    const viewport = answerViewportRef.current;
    if (viewport && answer) {
      viewport.scrollTop = viewport.scrollHeight;
    }
  }, [answer]);

  useEffect(() => {
    if (!expanded) return;
    const timer = window.setTimeout(() => textareaRef.current?.focus(), 140);
    return () => window.clearTimeout(timer);
  }, [expanded]);

  useEffect(() => {
    if (!expanded) return;
    const handleEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        setExpanded(false);
      }
    };

    window.addEventListener("keydown", handleEscape);
    return () => window.removeEventListener("keydown", handleEscape);
  }, [expanded]);

  const ask = () => {
    const normalizedQuestion = question.trim();
    if (!normalizedQuestion || loading) return;
    if (!tokens?.accessToken) {
      setLocalError("请先登录后使用多轮问答。");
      setExpanded(true);
      return;
    }

    setLocalError(null);
    setSubmittedQuestion(normalizedQuestion);
    setQuestion("");
    start(resolveApiUrl("/api/v1/knowposts/qa/chat/stream"), {
      method: "POST",
      body: {
        conversationId,
        scope: "global",
        postId: null,
        question: normalizedQuestion,
        topK
      },
      onMeta: (meta) => {
        if (typeof meta === "object" && meta !== null && "conversationId" in meta) {
          setConversationId(String((meta as { conversationId: string }).conversationId));
        }
      }
    });
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

  const useSuggestion = (text: string) => {
    setQuestion(text);
    textareaRef.current?.focus();
  };

  return (
    <div className={styles.dock}>
      {expanded ? (
        <section id="global-rag-panel" className={styles.panel} aria-labelledby="global-rag-title">
          <header className={styles.header}>
            <div className={styles.titleGroup}>
              <span className={styles.spark} aria-hidden="true">
                <SparkIcon width={18} height={18} />
              </span>
              <div>
                <h2 id="global-rag-title" className={styles.title}>知光智能版</h2>
                <span className={styles.scope}>知识库问答助手</span>
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
              <button
                type="button"
                className={styles.windowButton}
                onClick={() => setExpanded(false)}
                aria-label="收起客服咨询"
                title="收起"
              >
                <MinimizeIcon width={16} height={16} aria-hidden="true" />
              </button>
              <button
                type="button"
                className={styles.windowButton}
                onClick={() => setExpanded(false)}
                aria-label="关闭客服咨询"
                title="关闭"
              >
                <CloseIcon width={16} height={16} aria-hidden="true" />
              </button>
            </div>
          </header>

          <div className={styles.greeting}>
            <strong>你好！我是知光小助手</strong>
            <span>很高兴为你服务，有什么可以帮到你？</span>
          </div>

          <div ref={answerViewportRef} className={styles.answerViewport} aria-live="polite">
            {!submittedQuestion ? (
              <div className={styles.emptyState}>
                <span>你可以问我：</span>
                <button type="button" onClick={() => useSuggestion("如何快速开始使用知光？")}>
                  如何快速开始使用知光？
                  <ArrowRightIcon width={14} height={14} aria-hidden="true" />
                </button>
                <button type="button" onClick={() => useSuggestion("知文支持哪些格式？")}>
                  知文支持哪些格式？
                  <ArrowRightIcon width={14} height={14} aria-hidden="true" />
                </button>
                <button type="button" onClick={() => useSuggestion("如何加入会员？")}>
                  如何加入会员？
                  <ArrowRightIcon width={14} height={14} aria-hidden="true" />
                </button>
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
            {localError ? <div className={styles.error} role="alert">{localError}</div> : null}
            {error ? <div className={styles.error} role="alert">{error}</div> : null}
          </div>

          <form className={styles.composer} onSubmit={handleSubmit}>
            <textarea
              ref={textareaRef}
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={handleKeyDown}
              maxLength={500}
              rows={2}
              placeholder="请输入你的问题..."
              aria-label="输入知识库问题"
            />
            <div className={styles.actions}>
              <button
                type="button"
                className={styles.stopButton}
                onClick={stop}
                disabled={!loading}
                title="停止生成"
                aria-label="停止生成"
              >
                <span aria-hidden="true" />
              </button>
              <button
                type="submit"
                className={styles.sendButton}
                disabled={loading || !question.trim()}
                aria-label="发送问题"
                title="发送"
              >
                <ArrowRightIcon width={18} height={18} aria-hidden="true" />
              </button>
            </div>
          </form>
        </section>
      ) : null}

      <button
        type="button"
        className={styles.floatButton}
        onClick={() => setExpanded((value) => !value)}
        aria-expanded={expanded}
        aria-controls="global-rag-panel"
        aria-label={expanded ? "收起客服咨询" : "展开客服咨询"}
        title={expanded ? "收起客服咨询" : "客服咨询"}
      >
        <ChatBubbleIcon width={24} height={24} aria-hidden="true" />
        <span className={styles.floatLabel}>客服咨询</span>
      </button>
    </div>
  );
};

export default GlobalRagChat;
