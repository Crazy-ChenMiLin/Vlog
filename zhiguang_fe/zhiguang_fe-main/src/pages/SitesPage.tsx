import { useMemo, useState } from "react";
import AppLayout from "@/components/layout/AppLayout";
import MainHeader from "@/components/layout/MainHeader";
import AuthStatus from "@/features/auth/AuthStatus";
import { ExternalLinkIcon } from "@/components/icons/Icon";
import styles from "./SitesPage.module.css";

interface SiteItem {
  id: string;
  name: string;
  domain: string;
  url: string;
  icon: string;
  category: "all" | "cloud" | "ai" | "tools" | "portal" | "service";
  categoryLabel: string;
  status: "online" | "current";
  statusText: string;
  description: string;
}

const SITES: SiteItem[] = [
  {
    id: "web",
    name: "知光社区",
    domain: "web.chenmilin.xyz",
    url: "https://web.chenmilin.xyz",
    icon: "✨",
    category: "portal",
    categoryLabel: "知识社区",
    status: "current",
    statusText: "当前站点",
    description: "当前所在的知识分享社区，支持瀑布流排版、RAG 知识检索与技术卡片互动。"
  },
  {
    id: "note",
    name: "Note 知识与记忆看板",
    domain: "note.chenmilin.xyz",
    url: "https://note.chenmilin.xyz",
    icon: "📓",
    category: "portal",
    categoryLabel: "知识看板",
    status: "online",
    statusText: "在线运行",
    description: "个人专属双链笔记与记忆中枢，无缝聚合 Obsidian 知识写作活动与 Anki 间隔重复复习数据追踪。"
  },
  {
    id: "svg",
    name: "SVG → MP4 · 抖音竖版生成器",
    domain: "svg.chenmilin.xyz",
    url: "https://svg.chenmilin.xyz",
    icon: "🎬",
    category: "tools",
    categoryLabel: "创作工具",
    status: "online",
    statusText: "在线运行",
    description: "SVG 动画一键渲染转换为 1080×1920 抖音竖版高清视频，内置推近、拉远、斜向漂移等运镜动效，提供同一套接口本地与云端通用渲染。"
  },
  {
    id: "openlist",
    name: "OpenList 云盘共享",
    domain: "openlist.chenmilin.xyz",
    url: "https://openlist.chenmilin.xyz",
    icon: "📂",
    category: "cloud",
    categoryLabel: "云盘存储",
    status: "online",
    statusText: "在线运行",
    description: "基于韩国云与天翼云盘搭建的公开文件共享中心，海量资源支持在线浏览与高速下载。"
  },
  {
    id: "ai",
    name: "ChenMiLin AI 助手",
    domain: "ai.chenmilin.xyz",
    url: "https://ai.chenmilin.xyz",
    icon: "🤖",
    category: "ai",
    categoryLabel: "人工智能",
    status: "online",
    statusText: "在线运行",
    description: "个人部署的大模型智能对话平台，支持多轮深度对话、技术答疑与代码生成辅助。"
  },
  {
    id: "approval",
    name: "飞书审批工作台",
    domain: "approval.chenmilin.xyz",
    url: "https://approval.chenmilin.xyz",
    icon: "📋",
    category: "tools",
    categoryLabel: "协同办公",
    status: "online",
    statusText: "在线运行",
    description: "自动化业务审批控制台，深度对接飞书工作流，实现流程快速流转与移动化审批。"
  },
  {
    id: "wps",
    name: "WPS 自动审批控制台",
    domain: "wpsauto-approval.chenmilin.xyz",
    url: "https://wpsauto-approval.chenmilin.xyz",
    icon: "📑",
    category: "tools",
    categoryLabel: "协同办公",
    status: "online",
    statusText: "在线运行",
    description: "专为金山 WPS 文档与表格打造的自动化审批流，支持多表数据联动与自动回填。"
  },
  {
    id: "cookie",
    name: "Cookie Hub 凭据中枢",
    domain: "cookie.chenmilin.xyz",
    url: "https://cookie.chenmilin.xyz",
    icon: "🔐",
    category: "service",
    categoryLabel: "系统服务",
    status: "online",
    statusText: "在线运行",
    description: "基于 Cloudflare 的中央凭据调度与管理中枢，保障跨服务自动化安全联动与刷新。"
  },
  {
    id: "blog",
    name: "个人技术博客",
    domain: "blog.chenmilin.xyz",
    url: "https://blog.chenmilin.xyz",
    icon: "📝",
    category: "portal",
    categoryLabel: "博客门户",
    status: "online",
    statusText: "持续更新",
    description: "站长个人技术沉淀博客，记录分布式架构演进、中间件实战调优与全栈开发心得。"
  },
  {
    id: "vpn",
    name: "VPN 专用网络控制台",
    domain: "vpn.chenmilin.xyz",
    url: "https://vpn.chenmilin.xyz",
    icon: "🌐",
    category: "service",
    categoryLabel: "系统服务",
    status: "online",
    statusText: "运行中",
    description: "个人专用网络访问节点与连接控制台，保障远程主机与内网资源的安全互联。"
  }
];

const CATEGORIES = [
  { key: "all", label: "全部站点" },
  { key: "cloud", label: "云盘存储" },
  { key: "ai", label: "AI 助手" },
  { key: "tools", label: "办公与工具" },
  { key: "portal", label: "博客与主页" },
  { key: "service", label: "系统服务" }
] as const;

const SitesPage = () => {
  const [selectedCategory, setSelectedCategory] = useState<string>("all");

  const filteredSites = useMemo(() => {
    if (selectedCategory === "all") return SITES;
    return SITES.filter((item) => item.category === selectedCategory);
  }, [selectedCategory]);

  return (
    <AppLayout
      header={
        <MainHeader
          headline="站点矩阵"
          subtitle="陈米林 (chenmilin.xyz) 旗下服务与网站导航"
          rightSlot={<AuthStatus />}
        />
      }
      variant="cardless"
    >
      <div className={styles.wrapper}>
        <div className={styles.heroCard}>
          <div className={styles.heroBadge}>🌐 chenmilin.xyz 矩阵导航</div>
          <h2 className={styles.heroTitle}>站长生态站点与服务</h2>
          <p className={styles.heroDesc}>
            欢迎探索陈米林旗下的全部在线网站与服务！涵盖个人网盘、AI 对话工具、自动化审批流、个人技术博客与云端系统服务，点击卡片即可直接跳转访问。
          </p>
        </div>

        <div className={styles.filterBar}>
          {CATEGORIES.map((cat) => {
            const count =
              cat.key === "all"
                ? SITES.length
                : SITES.filter((s) => s.category === cat.key).length;
            const isActive = selectedCategory === cat.key;
            return (
              <button
                key={cat.key}
                type="button"
                className={isActive ? `${styles.filterBtn} ${styles.filterBtnActive}` : styles.filterBtn}
                onClick={() => setSelectedCategory(cat.key)}
              >
                {cat.label}
                <span className={styles.filterCount}>{count}</span>
              </button>
            );
          })}
        </div>

        <div className={styles.grid}>
          {filteredSites.map((site) => (
            <div key={site.id} className={styles.card}>
              <div>
                <div className={styles.cardHeader}>
                  <div className={styles.cardTitleWrap}>
                    <div className={styles.cardIcon}>{site.icon}</div>
                    <div className={styles.cardNameBlock}>
                      <h3 className={styles.cardTitle}>{site.name}</h3>
                      <span className={styles.cardDomain}>{site.domain}</span>
                    </div>
                  </div>
                  {site.status === "current" ? (
                    <span className={styles.badgeCurrent}>
                      <span className={styles.statusDot} />
                      {site.statusText}
                    </span>
                  ) : (
                    <span className={styles.badgeOnline}>
                      <span className={styles.statusDot} />
                      {site.statusText}
                    </span>
                  )}
                </div>

                <p className={styles.cardDesc}>{site.description}</p>
              </div>

              <div className={styles.cardFooter}>
                <span className={styles.tagPill}>{site.categoryLabel}</span>
                {site.status === "current" ? (
                  <span className={styles.visitBtnCurrent}>正在访问</span>
                ) : (
                  <a
                    href={site.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className={styles.visitBtn}
                  >
                    访问站点
                    <ExternalLinkIcon width={14} height={14} />
                  </a>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>
    </AppLayout>
  );
};

export default SitesPage;
