import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ArrowRightIcon, ChatBubbleIcon, CloseIcon, MinimizeIcon, SparkIcon } from "@/components/icons/Icon";
import { useAuth } from "@/context/AuthContext";
import { AgentStep, useRagStream } from "@/features/rag/hooks/useRagStream";
import { resolveApiUrl } from "@/services/apiClient";
import styles from "./GlobalRagChat.module.css";

const TOP_K_OPTIONS = [3, 5, 8, 12];

type ChatTurn = {
  id: string;
  question: string;
  answer: string;
  agentSteps: AgentStep[];
  loading: boolean;
  error: string | null;
};

const GlobalRagChat = () => {
  const [expanded, setExpanded] = useState(false);
  const [question, setQuestion] = useState("");
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [agentPanelOpen, setAgentPanelOpen] = useState(true);
  const [localError, setLocalError] = useState<string | null>(null);
  const [topK, setTopK] = useState(5);
  const answerViewportRef = useRef<HTMLDivElement | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const activeTurnIdRef = useRef<string | null>(null);
  const { answer, agentSteps, loading, error, start, stop } = useRagStream();
  const { tokens } = useAuth();

  useEffect(() => {
    const viewport = answerViewportRef.current;
    if (viewport && turns.length) {
      viewport.scrollTop = viewport.scrollHeight;
    }
  }, [turns]);

  useEffect(() => {
    const activeTurnId = activeTurnIdRef.current;
    if (!activeTurnId) return;
    setTurns((current) => current.map((turn) => (
      turn.id === activeTurnId
        ? { ...turn, answer, agentSteps, loading, error }
        : turn
    )));
  }, [answer, agentSteps, loading, error]);

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
    setAgentPanelOpen(true);
    setQuestion("");

    const turnId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    activeTurnIdRef.current = turnId;
    setTurns((current) => [
      ...current,
      {
        id: turnId,
        question: normalizedQuestion,
        answer: "",
        agentSteps: [],
        loading: true,
        error: null
      }
    ]);

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
                <h2 id="global-rag-title" className={styles.title}>知光知识库</h2>
                <span className={styles.scope}>全库智能问答</span>
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
                aria-label="收起知识问答"
                title="收起"
              >
                <MinimizeIcon width={16} height={16} aria-hidden="true" />
              </button>
              <button
                type="button"
                className={styles.windowButton}
                onClick={() => setExpanded(false)}
                aria-label="关闭知识问答"
                title="关闭"
              >
                <CloseIcon width={16} height={16} aria-hidden="true" />
              </button>
            </div>
          </header>

          <div className={styles.greeting}>
            <strong>你好！我是知光知识库助手</strong>
            <span>可以向我提问知识库里的技术内容。</span>
          </div>

          <div ref={answerViewportRef} className={styles.answerViewport} aria-live="polite">
            {!turns.length ? (
              <div className={styles.emptyState}>
                <span>试试这些全库问题：</span>
                <button type="button" onClick={() => useSuggestion("Redis 缓存穿透怎么解决？")}>
                  Redis 缓存穿透怎么解决？
                  <ArrowRightIcon width={14} height={14} aria-hidden="true" />
                </button>
                <button type="button" onClick={() => useSuggestion("RAG 的 rerank 是什么？")}>
                  RAG 的 rerank 是什么？
                  <ArrowRightIcon width={14} height={14} aria-hidden="true" />
                </button>
                <button type="button" onClick={() => useSuggestion("Spring Boot 事务传播是什么？")}>
                  Spring Boot 事务传播是什么？
                  <ArrowRightIcon width={14} height={14} aria-hidden="true" />
                </button>
                <button type="button" onClick={() => useSuggestion("Elasticsearch 倒排索引是什么？")}>
                  Elasticsearch 倒排索引是什么？
                  <ArrowRightIcon width={14} height={14} aria-hidden="true" />
                </button>
              </div>
            ) : (
              turns.map((turn) => (
                <article key={turn.id} className={styles.chatTurn}>
                  <div className={styles.questionBubble}>{turn.question}</div>
                  {turn.agentSteps.length ? (
                    <section className={styles.agentTrace} aria-label="Agent 执行过程">
                      <button
                        type="button"
                        className={styles.agentTraceToggle}
                        onClick={() => setAgentPanelOpen((value) => !value)}
                        aria-expanded={agentPanelOpen}
                      >
                        <span>过程</span>
                        <strong>{turn.agentSteps.length} 步</strong>
                        <i aria-hidden="true">{agentPanelOpen ? "收起" : "展开"}</i>
                      </button>
                      {agentPanelOpen ? (
                        <ol className={styles.agentStepList}>
                          {turn.agentSteps.map((step, index) => (
                            <li key={`${step.traceId}-${step.stepName}-${index}`} className={styles.agentStep}>
                              <span className={step.success ? styles.agentStepDot : styles.agentStepDotError} aria-hidden="true" />
                              <div>
                                <div className={styles.agentStepTitle}>
                                  <span>{step.title}</span>
                                  {step.costMs > 0 ? <em>{step.costMs}ms</em> : null}
                                </div>
                                <p>{step.summary || step.decision}</p>
                              </div>
                            </li>
                          ))}
                        </ol>
                      ) : null}
                    </section>
                  ) : null}
                  <div className={styles.answerBlock}>
                    {turn.answer ? (
                      <ReactMarkdown
                        remarkPlugins={[remarkGfm]}
                        components={{
                          a: ({ node, ...props }) => <a {...props} target="_blank" rel="noreferrer" />,
                          img: ({ node, ...props }) => <img {...props} alt={props.alt ?? ""} />
                        }}
                      >
                        {turn.answer}
                      </ReactMarkdown>
                    ) : turn.loading ? (
                      <div className={styles.thinking}>
                        <span />
                        <span />
                        <span />
                      </div>
                    ) : null}
                  </div>
                  {turn.error ? <div className={styles.error} role="alert">{turn.error}</div> : null}
                </article>
              ))
            )}
            {localError ? <div className={styles.error} role="alert">{localError}</div> : null}
          </div>

          <form className={styles.composer} onSubmit={handleSubmit}>
            <textarea
              ref={textareaRef}
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              onKeyDown={handleKeyDown}
              maxLength={500}
              rows={2}
              placeholder="请输入知识库问题..."
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
        aria-label={expanded ? "收起知识问答" : "展开知识问答"}
        title={expanded ? "收起知识问答" : "知识问答"}
      >
        <ChatBubbleIcon width={24} height={24} aria-hidden="true" />
        <span className={styles.floatLabel}>知识问答</span>
      </button>
    </div>
  );
};

export default GlobalRagChat;
