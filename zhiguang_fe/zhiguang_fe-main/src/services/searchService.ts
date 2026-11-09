import { ApiError, apiFetch } from "./apiClient";
import { isMockEnabled, searchMockItems } from "@/mock/mockData";
import type { SearchResponse, SuggestResponse } from "@/types/search";

const SEARCH_PREFIX = "/api/v1/search";

export const searchService = {
  query: async (params: { q: string; size?: number; tags?: string; after?: string | null }): Promise<SearchResponse> => {
    if (isMockEnabled()) {
      const res = searchMockItems(params.q, params.tags);
      return {
        items: res.items,
        nextAfter: null,
        hasMore: false
      };
    }

    const { q, size = 20, tags, after } = params;
    const usp = new URLSearchParams();
    usp.set("q", q);
    if (size) usp.set("size", String(size));
    if (tags) usp.set("tags", tags);
    if (after) usp.set("after", after);
    const url = `${SEARCH_PREFIX}?${usp.toString()}`;
    try {
      return await apiFetch<SearchResponse>(url);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        return apiFetch<SearchResponse>(url, { accessToken: null });
      }
      console.warn("搜索接口请求失败，降级使用 Mock 搜索结果:", error);
      const res = searchMockItems(params.q, params.tags);
      return {
        items: res.items,
        nextAfter: null,
        hasMore: false
      };
    }
  },

  suggest: async (prefix: string, size = 10): Promise<SuggestResponse> => {
    if (isMockEnabled()) {
      const q = prefix.trim().toLowerCase();
      const suggestions = ["RAG", "AI大模型", "向量数据库", "React 19", "Python 异步", "分布式架构", "TypeScript"]
        .filter(k => k.toLowerCase().includes(q))
        .slice(0, size);
      return { items: suggestions };
    }

    const usp = new URLSearchParams();
    usp.set("prefix", prefix);
    if (size) usp.set("size", String(size));
    const url = `${SEARCH_PREFIX}/suggest?${usp.toString()}`;
    try {
      return await apiFetch<SuggestResponse>(url);
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        return apiFetch<SuggestResponse>(url, { accessToken: null });
      }
      return { items: [] };
    }
  }
};
