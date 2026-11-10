import { useEffect, useRef } from "react";

export type UseInfiniteScrollOptions = {
  hasMore: boolean;
  loading: boolean;
  error?: string | null;
  itemCount: number;
  onLoadMore: () => void | Promise<void>;
  rootMargin?: string;
};

/**
 * 无感无限滚动 Hook：
 * 1. 通过 IntersectionObserver 在距离底部一定距离时提前自动加载下一页；
 * 2. 已到达底部时，用户再次向下滚动或上拉手势也会立即触发加载（含异常后下滑重试）。
 */
export const useInfiniteScroll = ({
  hasMore,
  loading,
  error,
  itemCount,
  onLoadMore,
  rootMargin = "0px 0px 320px 0px"
}: UseInfiniteScrollOptions) => {
  const sentinelRef = useRef<HTMLDivElement | null>(null);
  const onLoadMoreRef = useRef(onLoadMore);
  onLoadMoreRef.current = onLoadMore;

  useEffect(() => {
    const el = sentinelRef.current;
    if (!el || loading || !hasMore || error || typeof IntersectionObserver === "undefined") {
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          void onLoadMoreRef.current();
        }
      },
      {
        root: null,
        rootMargin,
        threshold: 0
      }
    );

    observer.observe(el);
    return () => observer.disconnect();
  }, [hasMore, loading, error, itemCount, rootMargin]);

  useEffect(() => {
    if (loading || !hasMore) return;

    let touchStartY: number | null = null;

    const isNearBottom = () => {
      const el = sentinelRef.current;
      if (!el) return false;
      return el.getBoundingClientRect().top <= window.innerHeight + 120;
    };

    const onWheel = (e: WheelEvent) => {
      if (e.deltaY > 0 && isNearBottom()) {
        void onLoadMoreRef.current();
      }
    };

    const onTouchStart = (e: TouchEvent) => {
      touchStartY = e.touches[0]?.clientY ?? null;
    };

    const onTouchEnd = (e: TouchEvent) => {
      if (touchStartY === null) return;
      const endY = e.changedTouches[0]?.clientY ?? touchStartY;
      // 手指向上滑动（下拉加载更多手势）
      if (touchStartY - endY > 24 && isNearBottom()) {
        void onLoadMoreRef.current();
      }
      touchStartY = null;
    };

    window.addEventListener("wheel", onWheel, { passive: true });
    window.addEventListener("touchstart", onTouchStart, { passive: true });
    window.addEventListener("touchend", onTouchEnd, { passive: true });

    return () => {
      window.removeEventListener("wheel", onWheel);
      window.removeEventListener("touchstart", onTouchStart);
      window.removeEventListener("touchend", onTouchEnd);
    };
  }, [hasMore, loading]);

  return sentinelRef;
};

export default useInfiniteScroll;
