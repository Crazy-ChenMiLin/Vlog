import { ApiError, apiFetch } from "./apiClient";
import type { SearchResponse, SuggestResponse } from "@/types/search";

const SEARCH_PREFIX = "/api/v1/search";

export const searchService = {
  query: async (params: { q: string; size?: number; tags?: string; after?: string | null }) => {
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
      throw error;
    }
  },

  suggest: async (prefix: string, size = 10) => {
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
      throw error;
    }
  }
};
