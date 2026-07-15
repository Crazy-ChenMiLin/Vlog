import { useCallback, useEffect, useRef, useState } from "react";

const CONNECTION_ERROR = "无法连接问答服务，请确认后端已启动后重试。";

type StartOptions = {
  method?: "GET" | "POST";
  body?: unknown;
  onMeta?: (meta: unknown) => void;
};

const getStoredAccessToken = (): string | null => {
  try {
    const raw = localStorage.getItem("zhiguang_auth_tokens");
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { accessToken?: string };
    return parsed.accessToken ?? null;
  } catch {
    return null;
  }
};

export const useRagStream = () => {
  const [answer, setAnswer] = useState("");
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
    stop();
    answerRef.current = "";
    setAnswer("");
    setError(null);
    setLoading(true);

    const controller = new AbortController();
    controllerRef.current = controller;

    const appendChunk = (chunk: string) => {
      if (controllerRef.current !== controller) return;
      answerRef.current += chunk;
      setAnswer(answerRef.current);
    };

    const consumeEvents = (input: string) => {
      let buffer = input;
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
        const headers: Record<string, string> = { Accept: "text/event-stream" };
        if (options.method === "POST") {
          headers["Content-Type"] = "application/json";
          const token = getStoredAccessToken();
          if (token) {
            headers.Authorization = `Bearer ${token}`;
          }
        }
        const response = await fetch(url, {
          method: options.method ?? "GET",
          headers,
          body: options.body ? JSON.stringify(options.body) : undefined,
          credentials: "include",
          signal: controller.signal
        });
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
      } catch (streamError) {
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
  }, [stop]);

  useEffect(() => stop, [stop]);

  return { answer, loading, error, start, stop };
};
