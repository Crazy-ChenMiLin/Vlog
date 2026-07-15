import { useCallback, useEffect, useState } from "react";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import CourseCard from "@/components/cards/CourseCard";
import LikeFavBar from "@/components/common/LikeFavBar";
import { knowpostService } from "@/services/knowpostService";
import type { FeedItem } from "@/types/knowpost";
import AuthStatus from "@/features/auth/AuthStatus";
import GlobalRagChat from "@/components/rag/GlobalRagChat";
import styles from "./HomePage.module.css";

const PAGE_SIZE = 20;

const HomePage = () => {
  const [items, setItems] = useState<FeedItem[]>([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState<boolean>(false);
  const [loadingMore, setLoadingMore] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const loadPage = useCallback(async (nextPage: number, append = false) => {
    if (append) {
      setLoadingMore(true);
    } else {
      setLoading(true);
    }
    setError(null);

    try {
      const resp = await knowpostService.feed(nextPage, PAGE_SIZE);
      setItems(prev => append ? [...prev, ...(resp.items ?? [])] : (resp.items ?? []));
      setPage(resp.page ?? nextPage);
      setHasMore(Boolean(resp.hasMore));
    } catch (err) {
      const msg = err instanceof Error ? err.message : "加载失败";
      setError(msg);
    } finally {
      if (append) {
        setLoadingMore(false);
      } else {
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      await loadPage(1);
    };
    if (!cancelled) {
      void run();
    }
    return () => {
      cancelled = true;
    };
  }, [loadPage]);

  const handleLoadMore = () => {
    if (!loadingMore && hasMore) {
      void loadPage(page + 1, true);
    }
  };

  return (
    <AppLayout
      header={
        <MainHeader
          headline="知光 · 让思想有温度，让知识会发光"
          rightSlot={<AuthStatus />}
        />
      }
      variant="cardless"
    >
      <GlobalRagChat />

      <section className={styles.feedSection} aria-labelledby="latest-knowposts-title">
        <header className={styles.feedHeader}>
          <h2 id="latest-knowposts-title">最新知文</h2>
          {!loading && items.length ? <span>{items.length} 篇</span> : null}
        </header>
        {error ? <div className={styles.feedError}>{error}</div> : null}
        <div className={styles.masonry}>
          {items.map(item => (
            <div key={item.id} className={styles.masonryItem}>
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
            </div>
          ))}
        </div>
        {loading ? <div className={styles.feedState}>加载中...</div> : null}
        {!loading && items.length === 0 ? <div className={styles.feedState}>暂无内容</div> : null}
        {!loading && items.length > 0 ? (
          <div className={styles.feedActions}>
            {hasMore ? (
              <button
                type="button"
                className={styles.loadMoreButton}
                onClick={handleLoadMore}
                disabled={loadingMore}
              >
                {loadingMore ? "加载中..." : "加载更多"}
              </button>
            ) : (
              <span className={styles.feedEnd}>已经到底了</span>
            )}
          </div>
        ) : null}
      </section>
    </AppLayout>
  );
};

export default HomePage;
