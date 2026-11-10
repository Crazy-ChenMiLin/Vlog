import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import SectionHeader from "@/components/common/SectionHeader";
import AuthStatus from "@/features/auth/AuthStatus";
import { useAuth } from "@/context/AuthContext";
import styles from "./ProfilePage.module.css";
import feedStyles from "./HomePage.module.css";
import CourseCard from "@/components/cards/CourseCard";
import LikeFavBar from "@/components/common/LikeFavBar";
import WaterfallLayout from "@/components/common/WaterfallLayout";
import { useInfiniteScroll } from "@/hooks/useInfiniteScroll";
import { estimateFeedItemHeight } from "@/pages/HomePage";
import { knowpostService } from "@/services/knowpostService";
import RelationCounters from "@/components/common/RelationCounters";
import type { FeedItem } from "@/types/knowpost";

const ProfilePage = () => {
  const { user, tokens } = useAuth();
  const displayName = user?.nickname ?? user?.phone ?? user?.email ?? "知光用户";
  const avatarInitial = displayName.trim().charAt(0) || "知";

  // 领域标签展示：仅解析 tagJson
  const tags = useMemo(() => {
    if (user && typeof user.tagJson === "string") {
      try {
        const parsed = JSON.parse(user.tagJson);
        return Array.isArray(parsed)
          ? parsed.filter((t) => typeof t === "string")
          : [];
      } catch {
        return [];
      }
    }
    return [];
  }, [user]);

  // 我的知文列表数据
  const [items, setItems] = useState<FeedItem[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const loadingMoreRef = useRef(false);
  const pageRef = useRef(1);
  const hasMoreRef = useRef(false);

  // 抽取成单一函数，供首次加载、编辑动作后及翻页复用
  const loadMine = useCallback(async (nextPage = 1, append = false) => {
    if (!tokens?.accessToken) return;
    if (append) {
      if (loadingMoreRef.current) return;
      loadingMoreRef.current = true;
      setLoadingMore(true);
    } else {
      setLoading(true);
    }
    setError(null);
    try {
      const resp = await knowpostService.mine(nextPage, 20, tokens.accessToken);
      const incoming = (resp.items ?? []) as FeedItem[];
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
  }, [tokens?.accessToken]);

  useEffect(() => {
    void loadMine(1, false);
  }, [loadMine]);

  const handleLoadMore = useCallback(() => {
    if (loadingMoreRef.current || !hasMoreRef.current) return;
    void loadMine(pageRef.current + 1, true);
  }, [loadMine]);

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
          headline="我的知光主页"
          rightSlot={<AuthStatus />}
        />
      }
    >
      <>
        <SectionHeader
          title="个人信息"
          subtitle="让同学们更快认识你"
          actions={<Link to="/profile/edit" className="ghost-button">编辑资料</Link>}
        />
        <div className={styles.profileGrid}>
          <div className={styles.avatarBox}>
            {user?.avatar ? (
              <img src={user.avatar} alt="avatar" className={styles.avatarImg} />
            ) : (
              <span>{avatarInitial}</span>
            )}
          </div>
          <div className={styles.infoBox}>
            <div className={styles.nickname}>{displayName}</div>
            <div className={styles.tags}>
              {tags.length > 0 ? (
                tags.map(tag => <span key={tag}>{tag}</span>)
              ) : (
                <span>未设置</span>
              )}
            </div>
          </div>
        </div>
        <div className={styles.bioBlock}>{user?.bio ?? "暂无简介"}</div>

        {/* 关系计数展示 */}
        {user?.id ? (
          <div style={{ marginTop: 8 }}>
            <RelationCounters userId={user.id} />
          </div>
        ) : null}

        <SectionHeader title="我的知文" subtitle="仅显示你已发布的知文" />
        {error && items.length === 0 ? <div style={{ color: "var(--color-danger)" }}>{error}</div> : null}
        {!user ? (
          <div style={{ color: "var(--color-text-muted)", padding: 12 }}>请登录后查看你的知文</div>
        ) : (
          <>
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
                  editable
                  onChanged={(action) => {
                    if (action === "delete") {
                      setItems(prev => prev.filter(x => x.id !== item.id));
                    } else {
                      void loadMine(1, false);
                    }
                  }}
                  footerExtra={<LikeFavBar entityId={item.id} compact initialCounts={{ like: item.likeCount ?? 0, fav: item.favoriteCount ?? 0 }} initialState={{ liked: item.liked, faved: item.faved }} />}
                />
              )}
            />
            {loading ? <div style={{ color: "var(--color-text-muted)", padding: 16, textAlign: "center" }}>加载中…</div> : null}
            {!loading && items.length === 0 ? (
              <div style={{ color: "var(--color-text-muted)", padding: 16, textAlign: "center" }}>暂无内容</div>
            ) : null}
            {!loading && items.length > 0 ? (
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
        )}
      </>
    </AppLayout>
  );
};

export default ProfilePage;
