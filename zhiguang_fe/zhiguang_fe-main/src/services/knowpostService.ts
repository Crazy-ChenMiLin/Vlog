import { apiFetch } from "./apiClient";
import {
  isMockEnabled,
  getMockFeed,
  getMockDetail,
  toggleMockLike,
  toggleMockFav,
  getMockCounters
} from "@/mock/mockData";
import type {
  CreateDraftResponse,
  PresignRequest,
  PresignResponse,
  ConfirmContentRequest,
  UpdateKnowPostRequest,
  FeedResponse,
  KnowpostDetailResponse,
  LikeActionResponse,
  FavActionResponse,
  CounterResponse,
  VisibleScope
} from "@/types/knowpost";

const KNOWPOST_PREFIX = "/api/v1/knowposts";
const STORAGE_PREFIX = "/api/v1/storage";

export const knowpostService = {
  createDraft: () =>
    apiFetch<CreateDraftResponse>(`${KNOWPOST_PREFIX}/drafts`, { method: "POST" }),

  presign: (payload: PresignRequest) =>
    apiFetch<PresignResponse>(`${STORAGE_PREFIX}/presign`, { method: "POST", body: payload }),

  confirmContent: (id: string, payload: ConfirmContentRequest) =>
    apiFetch<void>(`${KNOWPOST_PREFIX}/${id}/content/confirm`, { method: "POST", body: payload }),

  update: (id: string, payload: UpdateKnowPostRequest) =>
    apiFetch<void>(`${KNOWPOST_PREFIX}/${id}`, { method: "PATCH", body: payload }),

  publish: (id: string) =>
    apiFetch<void>(`${KNOWPOST_PREFIX}/${id}/publish`, { method: "POST" }),

  // 设置置顶（需鉴权）
  setTop: (id: string, isTop: boolean, accessToken?: string) => {
    if (isMockEnabled()) {
      return Promise.resolve();
    }
    return apiFetch<void>(`${KNOWPOST_PREFIX}/${id}/top`, {
      method: "PATCH",
      body: { isTop },
      accessToken
    });
  },

  // 设置可见性（需鉴权）
  setVisibility: (id: string, visible: VisibleScope, accessToken?: string) => {
    if (isMockEnabled()) {
      return Promise.resolve();
    }
    return apiFetch<void>(`${KNOWPOST_PREFIX}/${id}/visibility`, {
      method: "PATCH",
      body: { visible },
      accessToken
    });
  },

  // 删除知文（需鉴权）
  remove: (id: string, accessToken?: string) => {
    if (isMockEnabled()) {
      return Promise.resolve();
    }
    return apiFetch<void>(`${KNOWPOST_PREFIX}/${id}`, {
      method: "DELETE",
      accessToken
    });
  },

  // 获取首页 Feed 列表（公开内容）
  feed: async (page = 1, size = 20): Promise<FeedResponse> => {
    if (isMockEnabled()) {
      return getMockFeed(page, size);
    }
    try {
      return await apiFetch<FeedResponse>(`${KNOWPOST_PREFIX}/feed?page=${page}&size=${size}`);
    } catch (err) {
      console.warn("后端未响应，已自动切换至 Mock 演示数据:", err);
      return getMockFeed(page, size);
    }
  },

  // 获取我的知文（需鉴权）
  mine: async (page = 1, size = 20, accessToken?: string): Promise<FeedResponse> => {
    if (isMockEnabled()) {
      return getMockFeed(page, size);
    }
    try {
      return await apiFetch<FeedResponse>(`${KNOWPOST_PREFIX}/mine?page=${page}&size=${size}`, {
        accessToken
      });
    } catch (err) {
      console.warn("后端未响应，已自动切换至 Mock 演示数据:", err);
      return getMockFeed(page, size);
    }
  },

  // 获取知文详情（公开内容无需鉴权；非公开需要作者凭证）
  detail: async (id: string, accessToken?: string): Promise<KnowpostDetailResponse> => {
    if (isMockEnabled()) {
      return getMockDetail(id);
    }
    try {
      return await apiFetch<KnowpostDetailResponse>(`${KNOWPOST_PREFIX}/detail/${id}`, {
        accessToken: accessToken ?? null
      });
    } catch (err) {
      console.warn("后端未响应，已自动切换至 Mock 详情:", err);
      return getMockDetail(id);
    }
  },

  // 生成知文摘要（需鉴权）
  suggestDescription: (content: string, accessToken?: string) =>
    apiFetch<{ description: string }>(`${KNOWPOST_PREFIX}/description/suggest`, {
      method: "POST",
      body: { content },
      accessToken
    }),

  // 点赞/取消点赞
  like: async (entityId: string, accessToken?: string, entityType: string = "knowpost"): Promise<LikeActionResponse> => {
    if (isMockEnabled()) {
      return toggleMockLike(entityId, true);
    }
    try {
      return await apiFetch<LikeActionResponse>(`/api/v1/action/like`, {
        method: "POST",
        body: { entityType, entityId },
        accessToken
      });
    } catch {
      return toggleMockLike(entityId, true);
    }
  },
  unlike: async (entityId: string, accessToken?: string, entityType: string = "knowpost"): Promise<LikeActionResponse> => {
    if (isMockEnabled()) {
      return toggleMockLike(entityId, false);
    }
    try {
      return await apiFetch<LikeActionResponse>(`/api/v1/action/unlike`, {
        method: "POST",
        body: { entityType, entityId },
        accessToken
      });
    } catch {
      return toggleMockLike(entityId, false);
    }
  },

  // 收藏/取消收藏
  fav: async (entityId: string, accessToken?: string, entityType: string = "knowpost"): Promise<FavActionResponse> => {
    if (isMockEnabled()) {
      return toggleMockFav(entityId, true);
    }
    try {
      return await apiFetch<FavActionResponse>(`/api/v1/action/fav`, {
        method: "POST",
        body: { entityType, entityId },
        accessToken
      });
    } catch {
      return toggleMockFav(entityId, true);
    }
  },
  unfav: async (entityId: string, accessToken?: string, entityType: string = "knowpost"): Promise<FavActionResponse> => {
    if (isMockEnabled()) {
      return toggleMockFav(entityId, false);
    }
    try {
      return await apiFetch<FavActionResponse>(`/api/v1/action/unfav`, {
        method: "POST",
        body: { entityType, entityId },
        accessToken
      });
    } catch {
      return toggleMockFav(entityId, false);
    }
  },

  // 获取计数
  counters: async (entityId: string, accessToken?: string, entityType: string = "knowpost"): Promise<CounterResponse> => {
    if (isMockEnabled()) {
      return getMockCounters(entityId);
    }
    try {
      return await apiFetch<CounterResponse>(`/api/v1/counter/${entityType}/${entityId}?metrics=like,fav`, {
        accessToken
      });
    } catch {
      return getMockCounters(entityId);
    }
  }
};

/**
 * 直传到预签名 URL。注意：S3/OSS 会在响应头返回 ETag。
 */
export async function uploadToPresigned(putUrl: string, headers: Record<string, string>, file: File) {
  const resp = await fetch(putUrl, {
    method: "PUT",
    headers,
    body: file,
    credentials: "omit"
  });
  if (!resp.ok) {
    const text = await resp.text().catch(() => "");
    throw new Error(text || `上传失败：${resp.status}`);
  }
  const etag = resp.headers.get("ETag") || resp.headers.get("etag") || "";
  return { etag };
}

export async function computeSha256(file: File) {
  const buf = await file.arrayBuffer();
  const digest = await crypto.subtle.digest("SHA-256", buf);
  const hex = Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, "0")).join("");
  return hex;
}
