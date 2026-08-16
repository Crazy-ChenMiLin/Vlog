import { useCallback, useEffect, useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { useAuth } from "@/context/AuthContext";
import { knowpostService } from "@/services/knowpostService";
import { HeartIcon, BookmarkIcon } from "@/components/icons/Icon";
import styles from "./LikeFavBar.module.css";

type LikeFavBarProps = {
  entityId: string;
  entityType?: string; // default: "knowpost"
  initialCounts?: { like: number; fav: number };
  initialState?: { liked?: boolean; faved?: boolean };
  fetchCounts?: boolean; // if true, fetch counts on mount (requires auth per current policy)
  compact?: boolean;
  className?: string;
};

// 抖音式爱心动画：标准爱心 path + 粒子配色
const HEART_PATH =
  "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z";
const HEART_COLORS = ["#ff2d55", "#ff5e7e", "#ff7b9c", "#ffb3c6", "#ff4d6d", "#ffd166"];

type Particle = {
  id: number;
  dx: number;
  dy: number;
  dur: string;
  scale: number;
  rotate: number;
  color: string;
};

const LikeFavBar = ({
  entityId,
  entityType = "knowpost",
  initialCounts,
  initialState,
  fetchCounts = false,
  compact = false,
  className
}: LikeFavBarProps) => {
  const { tokens } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const iconSize = compact ? 18 : 20;

  const [likeCount, setLikeCount] = useState<number>(initialCounts?.like ?? 0);
  const [favCount, setFavCount] = useState<number>(initialCounts?.fav ?? 0);
  const [liked, setLiked] = useState<boolean>(initialState?.liked ?? false);
  const [faved, setFaved] = useState<boolean>(initialState?.faved ?? false);
  const [loadingLike, setLoadingLike] = useState(false);
  const [loadingFav, setLoadingFav] = useState(false);
  const [bigHeart, setBigHeart] = useState<number | null>(null);
  const [particles, setParticles] = useState<Particle[]>([]);
  const [likePop, setLikePop] = useState(false);
  const [countBump, setCountBump] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!fetchCounts) return;
      if (!tokens?.accessToken) return; // 当前策略：需鉴权
      try {
        const resp = await knowpostService.counters(entityId, tokens.accessToken, entityType);
        if (!cancelled) {
          const like = resp.counts?.like ?? 0;
          const fav = resp.counts?.fav ?? 0;
          setLikeCount(typeof like === "number" ? like : 0);
          setFavCount(typeof fav === "number" ? fav : 0);
        }
      } catch {
        // 忽略计数加载错误，保持初值
      }
    };
    run();
    return () => { cancelled = true; };
  }, [entityId, entityType, tokens?.accessToken, fetchCounts]);

  // 当初始状态变更时，同步到本地状态（例如从详情或列表传入）
  useEffect(() => {
    if (typeof initialState?.liked !== "undefined") {
      setLiked(!!initialState.liked);
    }
    if (typeof initialState?.faved !== "undefined") {
      setFaved(!!initialState.faved);
    }
  }, [initialState?.liked, initialState?.faved]);

  // 点赞时触发抖音式爱心动画：大爱心弹出 + 粒子爆炸 + 按钮弹跳 + 数字滚动
  const spawnHearts = useCallback(() => {
    setBigHeart(Date.now());
    const n = 10;
    const next: Particle[] = Array.from({ length: n }, (_, i) => {
      const ang = (Math.PI * 2 * i) / n + Math.random() * 0.5;
      const dist = 40 + Math.random() * 35;
      return {
        id: Date.now() + i + 1,
        dx: Math.cos(ang) * dist,
        dy: Math.sin(ang) * dist - 22,
        dur: (0.7 + Math.random() * 0.5).toFixed(2) + "s",
        scale: 0.6 + Math.random() * 0.8,
        rotate: Math.random() * 60 - 30,
        color: HEART_COLORS[Math.floor(Math.random() * HEART_COLORS.length)]
      };
    });
    setParticles((p) => [...p, ...next]);
    // 重新触发按钮弹跳 + 数字滚动动画（先移除再重加）
    setLikePop(false);
    setCountBump(false);
    requestAnimationFrame(() => {
      setLikePop(true);
      setCountBump(true);
    });
  }, []);

  const mustLogin = () => {
    navigate("/login", { state: { from: location.pathname } });
  };

  const onLikeClick = async (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation(); // 避免卡片 Link 导航
    if (!tokens?.accessToken) {
      mustLogin();
      return;
    }
    if (loadingLike) return;
    setLoadingLike(true);
    try {
      if (!liked) {
        const resp = await knowpostService.like(entityId, tokens.accessToken, entityType);
        setLiked(resp.liked);
        if (resp.changed && resp.liked) {
          setLikeCount((c) => c + 1);
          spawnHearts();
        }
      } else {
        const resp = await knowpostService.unlike(entityId, tokens.accessToken, entityType);
        setLiked(resp.liked);
        if (resp.changed && !resp.liked) setLikeCount((c) => Math.max(0, c - 1));
      }
    } catch {
      // 可选：提示错误
    } finally {
      setLoadingLike(false);
    }
  };

  const onFavClick = async (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!tokens?.accessToken) {
      mustLogin();
      return;
    }
    if (loadingFav) return;
    setLoadingFav(true);
    try {
      if (!faved) {
        const resp = await knowpostService.fav(entityId, tokens.accessToken, entityType);
        setFaved(resp.faved);
        if (resp.changed && resp.faved) setFavCount((c) => c + 1);
      } else {
        const resp = await knowpostService.unfav(entityId, tokens.accessToken, entityType);
        setFaved(resp.faved);
        if (resp.changed && !resp.faved) setFavCount((c) => Math.max(0, c - 1));
      }
    } catch {
      // 可选：提示错误
    } finally {
      setLoadingFav(false);
    }
  };

  return (
    <div className={`${styles._bar} ${compact ? styles.compact : ""} ${className ?? ""}`.trim()}>
      <div className={styles.likeWrap}>
        <button
          type="button"
          className={`${styles.btn} ${styles.likeBtn} ${liked ? styles.liked : ""} ${loadingLike ? styles.disabled : ""} ${likePop ? styles.pop : ""}`}
          onClick={onLikeClick}
          aria-pressed={liked}
          aria-label={liked ? "取消点赞" : "点赞"}
        >
          <HeartIcon width={iconSize} height={iconSize} />
          <span className={`${styles.count} ${countBump ? styles.bump : ""}`}>{likeCount}</span>
        </button>

        {bigHeart !== null ? (
          <span
            className={`${styles.bigHeart} ${styles.anim}`}
            onAnimationEnd={() => setBigHeart(null)}
            aria-hidden="true"
          >
            <svg viewBox="0 0 24 24" width={iconSize * 2.6} height={iconSize * 2.6}>
              <path d={HEART_PATH} fill="#ff2d55" stroke="#fff" strokeWidth="1.2" />
            </svg>
          </span>
        ) : null}

        {particles.map((p) => (
          <span
            key={p.id}
            className={`${styles.miniHeart} ${styles.burst}`}
            style={
              {
                "--dx": `${p.dx}px`,
                "--dy": `${p.dy}px`,
                "--dur": p.dur,
                transform: `translate(-50%,-50%) scale(${p.scale}) rotate(${p.rotate}deg)`
              } as React.CSSProperties
            }
            onAnimationEnd={() => setParticles((prev) => prev.filter((x) => x.id !== p.id))}
            aria-hidden="true"
          >
            <svg viewBox="0 0 24 24" width="16" height="16">
              <path d={HEART_PATH} fill={p.color} />
            </svg>
          </span>
        ))}
      </div>
      <button
        type="button"
        className={`${styles.btn} ${faved ? styles.faved : ""} ${loadingFav ? styles.disabled : ""}`}
        onClick={onFavClick}
        aria-pressed={faved}
        aria-label={faved ? "取消收藏" : "收藏"}
      >
        <BookmarkIcon width={iconSize} height={iconSize} />
        <span className={styles.count}>{favCount}</span>
      </button>
    </div>
  );
};

export default LikeFavBar;