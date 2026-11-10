import { useCallback, useEffect, useRef, useState } from "react";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import CourseCard from "@/components/cards/CourseCard";
import LikeFavBar from "@/components/common/LikeFavBar";
import WaterfallLayout from "@/components/common/WaterfallLayout";
import { useInfiniteScroll } from "@/hooks/useInfiniteScroll";
import { knowpostService } from "@/services/knowpostService";
import type { FeedItem } from "@/types/knowpost";
import AuthStatus from "@/features/auth/AuthStatus";
import GlobalRagChat from "@/features/rag/components/GlobalRagChat";
import styles from "./HomePage.module.css";

const PAGE_SIZE = 20;
const SHUFFLE_INTERVAL_MS = 30_000;

const shuffleItems = (items: FeedItem[]) => {
  const shuffled = [...items];
  for (let i = shuffled.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
  }
  return shuffled;
};

export const estimateFeedItemHeight = (item: FeedItem): number => {
  let h = item.coverImage ? 210 : 160;
  const titleLen = item.title ? item.title.length : 0;
  h += titleLen > 15 ? 48 : 24;
  const descLen = item.description ? item.description.trim().length : 0;
  if (descLen > 0) {
    h += descLen > 22 ? 40 : 20;
  }
  const tagCount = item.tags ? item.tags.length : 0;
  if (tagCount > 0) {
    h += tagCount > 3 ? 50 : 26;
  }
  h += 38; // footer
  h += 48; // padding and gaps
  return h;
};

const HomePage = () => {
  const [items, setItems] = useState<FeedItem[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState<boolean>(false);
  const [loadingMore, setLoadingMore] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const loadingMoreRef = useRef(false);
  const pageRef = useRef(1);
  const hasMoreRef = useRef(false);

  const loadPage = useCallback(async (nextPage: number, append = false) => {
    if (append) {
      if (loadingMoreRef.current) return;
      loadingMoreRef.current = true;
      setLoadingMore(true);
    } else {
      setLoading(true);
    }
    setError(null);

    try {
      const resp = await knowpostService.feed(nextPage, PAGE_SIZE);
      const incoming = resp.items ?? [];
      const resolvedPage = resp.page ?? nextPage;
      const resolvedHasMore = append && incoming.length === 0 ? false : Boolean(resp.hasMore);

      setItems(prev => {
        if (!append) return incoming;
        const seen = new Set(prev.map(x => x.id));
        const uniqueIncoming = incoming.filter(x => !seen.has(x.id));
        return [...prev, ...uniqueIncoming];
      });
      pageRef.current = resolvedPage;
      hasMoreRef.current = resolvedHasMore;
      setHasMore(resolvedHasMore);
    } catch (err) {
      const msg = err instanceof Error ? err.message : "加载失败";
      setError(msg);
    } finally {
      if (append) {
        loadingMoreRef.current = false;
        setLoadingMore(false);
      } else {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void loadPage(1);
  }, [loadPage]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setItems(prev => (prev.length > 1 ? shuffleItems(prev) : prev));
    }, SHUFFLE_INTERVAL_MS);

    return () => window.clearInterval(timer);
  }, []);

  const handleLoadMore = useCallback(() => {
    if (loadingMoreRef.current || !hasMoreRef.current) return;
    void loadPage(pageRef.current + 1, true);
  }, [loadPage]);

  const sentinelRef = useInfiniteScroll({
    hasMore,
    loading: loading || loadingMore,
    error,
    itemCount: items.length,
    onLoadMore: handleLoadMore
  });

  return (
    <AppLayout
      header={
        <MainHeader
          headline="知光"
          rightSlot={<AuthStatus />}
        />
      }
      variant="cardless"
    >
      <GlobalRagChat />

      <section className={styles.feedSection} aria-labelledby="latest-knowposts-title">
        <header className={styles.feedHeader}>
          {!loading && items.length ? <span>{items.length} 篇</span> : null}
        </header>
        {error && items.length === 0 ? <div className={styles.feedError}>{error}</div> : null}

        <WaterfallLayout
          items={items}
          getItemKey={(item) => item.id}
          estimateHeight={estimateFeedItemHeight}
          gap={18}
          renderItem={(item) => (
            <CourseCard
              id={item.id}
              title={item.title}
              summary={item.description ?? ""}
              tags={item.tags ?? []}
              authorTags={(() => {
                try {
                  return item.tagJson ? (JSON.parse(item.tagJson) as unknown[]).filter((t) => typeof t === "string") as string[] : [];
                } catch {
                  return [];
                }
              })()}
              teacher={{ name: item.authorNickname, avatarUrl: item.authorAvatar ?? item.authorAvator }}
              coverImage={item.coverImage}
              to={`/post/${item.id}`}
              footerExtra={<LikeFavBar entityId={item.id} compact initialCounts={{ like: item.likeCount ?? 0, fav: item.favoriteCount ?? 0 }} initialState={{ liked: item.liked, faved: item.faved }} />}
            />
          )}
        />

        {loading ? <div className={styles.feedState}>加载中...</div> : null}
        {!loading && items.length === 0 ? <div className={styles.feedState}>暂无内容</div> : null}
        {!loading && items.length > 0 ? (
          <div ref={sentinelRef} className={styles.feedActions}>
            {loadingMore ? (
              <div className={styles.feedLoadingMore}>
                <span className={styles.loadingSpinner} aria-hidden="true" />
                <span>正在加载更多...</span>
              </div>
            ) : error && hasMore ? (
              <button
                type="button"
                className={styles.retryButton}
                onClick={handleLoadMore}
              >
                加载失败，下滑或点击重试
              </button>
            ) : !hasMore ? (
              <span className={styles.feedEnd}>已经到底了</span>
            ) : null}
          </div>
        ) : null}
      </section>
    </AppLayout>
  );
};

export default HomePage;
