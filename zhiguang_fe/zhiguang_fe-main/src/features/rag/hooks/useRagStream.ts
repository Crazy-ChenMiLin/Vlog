import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "@/context/AuthContext";

const CONNECTION_ERROR = "问答服务暂时不可用，请稍后再试。";

type StartOptions = {
  method?: "GET" | "POST";
  body?: unknown;
  onMeta?: (meta: unknown) => void;
  onAgentStep?: (step: AgentStep) => void;
};

export type AgentStep = {
  traceId: string;
  stepName: string;
  title: string;
  decision: string;
  success: boolean;
  costMs: number;
  summary: string;
};

export const useRagStream = () => {
  const { tokens, reloadUser } = useAuth();
  const accessToken = tokens?.accessToken;
  const navigate = useNavigate();
  const { pathname, search, hash } = useLocation();
  const returnTo = pathname + search + hash;
  const [answer, setAnswer] = useState("");
  const [agentSteps, setAgentSteps] = useState<AgentStep[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const answerRef = useRef("");

  const stop = useCallback(() => {
    const controller = controllerRef.current;
    controllerRef.current = null;
    controller?.abort();
    setLoading(false);
  }, []);

  const start = useCallback((url: string, options: StartOptions = {}) => {
    if (!accessToken) {
      navigate("/login", { state: { from: returnTo } });
      return;
    }

    stop();
    answerRef.current = "";
    setAnswer("");
    setAgentSteps([]);
    setError(null);
    setLoading(true);

    const controller = new AbortController();
    controllerRef.current = controller;

    const appendChunk = (chunk: string) => {
      if (controllerRef.current !== controller) return;
      answerRef.current += chunk;
      setAnswer(answerRef.current);
    };

    const consumeEvents = (buffer: string) => {
      let match = buffer.match(/\r?\n\r?\n/);
      while (match?.index !== undefined) {
        const eventBlock = buffer.slice(0, match.index);
        buffer = buffer.slice(match.index + match[0].length);
        const lines = eventBlock.split(/\r?\n/);
        const eventType = lines.find((line) => line.startsWith("event:"))?.slice(6).trim() || "message";
        const dataLines = lines
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5));
        if (dataLines.length) {
          const payload = dataLines.join("\n");
          if (eventType === "meta") {
            try {
              options.onMeta?.(JSON.parse(payload));
            } catch {
              options.onMeta?.(payload);
            }
          } else if (eventType === "agent_step") {
            try {
              const step = JSON.parse(payload) as AgentStep;
              setAgentSteps((current) => [...current, step]);
              options.onAgentStep?.(step);
            } catch {
              // Ignore malformed observability events; answer streaming should keep working.
            }
          } else if (eventType === "message") {
            appendChunk(payload === "" ? "\n" : payload);
          }
        }
        match = buffer.match(/\r?\n\r?\n/);
      }
      return buffer;
    };

    void (async () => {
      try {
        const headers: Record<string, string> = {
          Accept: "text/event-stream",
          Authorization: `Bearer ${accessToken}`
        };
        if (options.method === "POST") {
          headers["Content-Type"] = "application/json";
        }
        const response = await fetch(url, {
          method: options.method ?? "GET",
          headers,
          body: options.body ? JSON.stringify(options.body) : undefined,
          credentials: "include",
          signal: controller.signal
        });
        if (controllerRef.current !== controller) return;
        if (response.status === 403) {
          navigate("/vip", { state: { returnTo } });
          return;
        }
        if (response.status === 401) {
          await reloadUser();
          if (controllerRef.current === controller) {
            navigate("/login", { state: { from: returnTo } });
          }
          return;
        }
        if (!response.ok || !response.body) {
          throw new Error(`RAG stream failed: ${response.status}`);
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          buffer = consumeEvents(buffer);
        }
        buffer += decoder.decode();
        if (buffer) {
          consumeEvents(`${buffer}\n\n`);
        }
      } catch {
        if (!controller.signal.aborted && controllerRef.current === controller) {
          setError(CONNECTION_ERROR);
        }
      } finally {
        if (controllerRef.current === controller) {
          controllerRef.current = null;
          setLoading(false);
        }
      }
    })();
  }, [accessToken, navigate, reloadUser, returnTo, stop]);

  useEffect(() => stop, [stop]);

  return { answer, agentSteps, loading, error, start, stop };
};
