import { useCallback, useRef, useState } from "react";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import SectionHeader from "@/components/common/SectionHeader";
import SearchBar from "@/components/common/SearchBar";
import AuthStatus from "@/features/auth/AuthStatus";
import styles from "./SearchPage.module.css";
import feedStyles from "./HomePage.module.css";
import { searchService } from "@/services/searchService";
import type { FeedItem } from "@/types/knowpost";
import CourseCard from "@/components/cards/CourseCard";
import LikeFavBar from "@/components/common/LikeFavBar";
import WaterfallLayout from "@/components/common/WaterfallLayout";
import { useInfiniteScroll } from "@/hooks/useInfiniteScroll";
import { estimateFeedItemHeight } from "@/pages/HomePage";
import { useAuth } from "@/context/AuthContext";

const SearchPage = () => {
  const [q, setQ] = useState("");
  const [tags] = useState(""); // 逗号分隔
  const [size] = useState<number>(20);
  const [items, setItems] = useState<FeedItem[]>([]);
  const [hasMore, setHasMore] = useState<boolean>(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [suggestLoading, setSuggestLoading] = useState(false);
  const debounceRef = useRef<number | null>(null);
  const loadingMoreRef = useRef(false);
  const afterRef = useRef<string | null>(null);
  const hasMoreRef = useRef(false);
  const { user } = useAuth();
  const [showLoginHint, setShowLoginHint] = useState(false);

  const executeSearch = async (keyword: string) => {
    const text = keyword.trim();
    if (!text) return;
    if (!user) {
      setShowLoginHint(true);
    }
    setQ(text);
    setLoading(true);
    try {
      const resp = await searchService.query({ q: text, size, tags: tags.trim() || undefined });
      const incoming = resp.items ?? [];
      afterRef.current = resp.nextAfter ?? null;
      hasMoreRef.current = Boolean(resp.hasMore);
      setItems(incoming);
      setHasMore(Boolean(resp.hasMore));
    } catch {
      afterRef.current = null;
      hasMoreRef.current = false;
      setItems([]);
      setHasMore(false);
    } finally {
      setLoading(false);
    }
  };

  const handleLoadMore = useCallback(async () => {
    if (loadingMoreRef.current || !hasMoreRef.current || !q.trim() || !afterRef.current) return;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    try {
      const resp = await searchService.query({
        q: q.trim(),
        size,
        tags: tags.trim() || undefined,
        after: afterRef.current
      });
      const incoming = resp.items ?? [];
      const nextHasMore = incoming.length === 0 ? false : Boolean(resp.hasMore);
      setItems(prev => {
        const seen = new Set(prev.map(x => x.id));
        const uniqueIncoming = incoming.filter(x => !seen.has(x.id));
        return [...prev, ...uniqueIncoming];
      });
      afterRef.current = resp.nextAfter ?? null;
      hasMoreRef.current = nextHasMore;
      setHasMore(nextHasMore);
    } catch {
      // 保持已有数据
    } finally {
      loadingMoreRef.current = false;
      setLoadingMore(false);
    }
  }, [q, size, tags]);

  const sentinelRef = useInfiniteScroll({
    hasMore,
    loading: loading || loadingMore,
    itemCount: items.length,
    onLoadMore: handleLoadMore
  });

  return (
    <AppLayout
      header={
        <MainHeader
          headline="搜索你想学习的知识"
          rightSlot={<AuthStatus />}
        >
          <SearchBar
            placeholder="搜索你想学习的知识..."
            value={q}
            suggestions={suggestions}
            suggestLoading={suggestLoading}
            onSuggestionClick={(s) => {
              executeSearch(s);
            }}
            onChange={(val) => {
              setQ(val);
              // 前缀联想：300ms 防抖
              if (debounceRef.current) window.clearTimeout(debounceRef.current);
              debounceRef.current = window.setTimeout(async () => {
                if (!val.trim()) { setSuggestions([]); return; }
                try {
                  setSuggestLoading(true);
                  const resp = await searchService.suggest(val.trim(), 10);
                  setSuggestions(resp.items ?? []);
                } catch {
                  setSuggestions([]);
                } finally {
                  setSuggestLoading(false);
                }
              }, 300);
            }}
            onSubmit={() => executeSearch(q)}
          />
        </MainHeader>
      }
    >
      <>
        {showLoginHint && !user ? (
          <div className={styles.loginHint}>
            当前为未登录状态，登录后可获得更完整的推荐与学习记录。
          </div>
        ) : null}
        <SectionHeader title="搜索结果" subtitle={loading ? "加载中…" : items.length ? `共 ${items.length} 条（可能有更多）` : "请输入关键词后搜索"} />

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
              isTop={item.isTop}
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

        {items.length > 0 ? (
          <div ref={sentinelRef} className={feedStyles.feedActions}>
            {loadingMore ? (
              <div className={feedStyles.feedLoadingMore}>
                <span className={feedStyles.loadingSpinner} aria-hidden="true" />
                <span>正在加载更多...</span>
              </div>
            ) : !hasMore ? (
              <span className={feedStyles.feedEnd}>已经到底了</span>
            ) : null}
          </div>
        ) : null}
      </>
    </AppLayout>
  );
};

export default SearchPage;
