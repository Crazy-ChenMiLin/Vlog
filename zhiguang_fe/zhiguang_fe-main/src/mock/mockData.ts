import type { FeedItem, KnowpostDetailResponse } from "@/types/knowpost";

export const isMockEnabled = (): boolean => {
  if (typeof window === "undefined") return true;
  const val = localStorage.getItem("zhiguang_mock_enabled");
  return val === null ? true : val === "true";
};

export const setMockEnabled = (enabled: boolean): void => {
  if (typeof window !== "undefined") {
    localStorage.setItem("zhiguang_mock_enabled", String(enabled));
    window.dispatchEvent(new CustomEvent("zhiguang_mock_changed", { detail: enabled }));
  }
};

export const MOCK_FEED_ITEMS: FeedItem[] = [
  {
    id: "102003327239193269",
    title: "【知光精选】AI大模型 · RAG (对比辨析)",
    description: "AI大模型 RAG 对比辨析：检索增强生成先获取相关文档再作答，缓解幻觉。",
    tags: ["RAG", "AI大模型", "AI", "大模型"],
    authorNickname: "知光用户4da460e5",
    authorAvatar: "",
    likeCount: 108708,
    favoriteCount: 25002,
    liked: false,
    faved: false,
    isTop: true,
    visible: "public"
  },
  {
    id: "102003327239193270",
    title: "【知光精选】AI大模型 · RAG (面试要点)",
    description: "AI大模型 RAG 面试要点：检索增强生成先获取相关文档再作答，缓解幻觉。",
    tags: ["RAG", "AI大模型", "AI", "大模型"],
    authorNickname: "知光用户8f6544db",
    authorAvatar: "",
    likeCount: 95879,
    favoriteCount: 22052,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193271",
    title: "【知光精选】AI大模型 · 向量数据库 (面试要点)",
    description: "AI大模型 向量数据库 面试要点：存 Embedding 并做相似检索，是 RAG 的记忆层。",
    tags: ["向量数据库", "AI大模型", "AI", "大模型", "RAG"],
    authorNickname: "知光用户4da460e5",
    authorAvatar: "",
    likeCount: 70798,
    favoriteCount: 8495,
    liked: true,
    faved: true,
    visible: "public"
  },
  {
    id: "102003327239193272",
    title: "AI大模型 RAG 核心原理解析与企业落地实践",
    description: "系统梳理从 Document Chunking 分块切分、Embedding 嵌入到 Re-ranking 重排序的完整 Pipeline 搭建细节。",
    tags: ["RAG", "大模型", "知识库"],
    authorNickname: "知光架构师",
    authorAvatar: "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?w=100&auto=format&fit=crop&q=80",
    likeCount: 52104,
    favoriteCount: 16320,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193273",
    title: "今日学习打卡：前沿多模态大模型与具身智能视觉导读",
    description: "从 Vision Transformer 到多模态跨模态对齐，整理了 10 篇顶会经典论文的干货精炼笔记，附思维导图。",
    coverImage: "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=600&auto=format&fit=crop&q=80",
    tags: ["学习打卡", "多模态", "具身智能", "论文导读"],
    authorNickname: "知光小丸子",
    authorAvatar: "https://images.unsplash.com/photo-1494790108377-be9c29b29330?w=100&auto=format&fit=crop&q=80",
    likeCount: 34210,
    favoriteCount: 8180,
    liked: true,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193274",
    title: "深入浅出分布式系统设计与高并发实战体系",
    description: "服务雪崩防御、分布式锁设计权衡、Raft 共识算法及 MySQL 分库分表平滑迁移经验分享。",
    coverImage: "https://images.unsplash.com/photo-1518770660439-4636190af475?w=600&auto=format&fit=crop&q=80",
    tags: ["后端架构", "分布式", "高并发", "系统设计"],
    authorNickname: "老张聊架构",
    authorAvatar: "https://images.unsplash.com/photo-1570295999919-56ceb5ecca61?w=100&auto=format&fit=crop&q=80",
    likeCount: 41920,
    favoriteCount: 12040,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193275",
    title: "React 19 全新特性解读与实战迁移全指南",
    description: "Action 机制、useOptimistic 乐观更新、React Compiler 自动记忆化原理，全面提升前端渲染效率。",
    coverImage: "https://images.unsplash.com/photo-1633356122544-f134324a6cee?w=600&auto=format&fit=crop&q=80",
    tags: ["React", "前端开发", "JavaScript", "Web架构"],
    authorNickname: "前端探险家",
    authorAvatar: "https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?w=100&auto=format&fit=crop&q=80",
    likeCount: 62890,
    favoriteCount: 18450,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193276",
    title: "Python 异步高并发编程：Asyncio 源码深度剖析",
    description: "事件循环机制、协程调度、Future 与 Task 源码拆解，彻底搞定 Python 异步网络通信。",
    tags: ["Python", "Asyncio", "并发编程"],
    authorNickname: "代码手艺人",
    authorAvatar: "",
    likeCount: 29810,
    favoriteCount: 7320,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193277",
    title: "UI/UX 设计美学：极简主义与现代感界面的融合之道",
    description: "探讨暖色系纸质感风格、柔和阴影、微交互动画与字体排印在提升产品质感中的关键细节。",
    coverImage: "https://images.unsplash.com/photo-1507238691740-187a5b1d37b8?w=600&auto=format&fit=crop&q=80",
    tags: ["UI设计", "用户体验", "极简美学", "设计系统"],
    authorNickname: "知光设计所",
    authorAvatar: "https://images.unsplash.com/photo-1580489944761-15a19d654956?w=100&auto=format&fit=crop&q=80",
    likeCount: 87400,
    favoriteCount: 31200,
    liked: true,
    faved: true,
    visible: "public"
  },
  {
    id: "102003327239193278",
    title: "Spring Boot 3.x 核心原理与微服务生产实战",
    description: "GraalVM 原生镜像打包、JDK 21 虚拟线程在 Spring 生态中的压测表现与最佳配置。",
    tags: ["Java", "SpringBoot", "微服务"],
    authorNickname: "后端修行者",
    authorAvatar: "",
    likeCount: 19430,
    favoriteCount: 5610,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193279",
    title: "TypeScript 类型体操进阶通关秘籍",
    description: "从基础泛型约束到模式匹配 infer、递归元组反转，带你彻底攻克复杂类型推导难关。",
    tags: ["TypeScript", "类型体操", "进阶编程"],
    authorNickname: "知光用户4da460e5",
    authorAvatar: "",
    likeCount: 48920,
    favoriteCount: 15300,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193280",
    title: "数据密集型应用系统设计 (DDIA) 精读笔记第 1 期",
    description: "从存储引擎 LSM-Tree 与 B-Tree 的性能对决，到多版本并发控制 (MVCC) 的深层实现。",
    coverImage: "https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?w=600&auto=format&fit=crop&q=80",
    tags: ["读书笔记", "系统架构", "分布式系统", "数据库"],
    authorNickname: "书海漫游",
    authorAvatar: "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&auto=format&fit=crop&q=80",
    likeCount: 76230,
    favoriteCount: 29800,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193281",
    title: "云原生与 Kubernetes 生产级运维避坑手册",
    description: "真实集群中 Pod 异常驱逐、网络抖动丢包排查、CoreDNS 缓存瓶颈调优一线经验总结。",
    tags: ["云原生", "K8s", "DevOps"],
    authorNickname: "运维猫咪",
    authorAvatar: "",
    likeCount: 18200,
    favoriteCount: 4390,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193282",
    title: "自然语言处理 (NLP) 经典算法演进全景图谱",
    description: "从词袋模型、Word2Vec、Seq2Seq 注意力机制，到 Transformer 与现代大模型的范式转移。",
    coverImage: "https://images.unsplash.com/photo-1620712943543-bcc4688e7485?w=600&auto=format&fit=crop&q=80",
    tags: ["NLP", "人工智能", "深度学习", "机器学习"],
    authorNickname: "知光用户8f6544db",
    authorAvatar: "",
    likeCount: 53120,
    favoriteCount: 16780,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193283",
    title: "前端极致性能工程：从白屏 3 秒到 0.8 秒优化纪实",
    description: "资源预加载分流、CSS 渲染层重绘规避、首屏骨架屏预渲染与现代打包分包策略演进实录。",
    tags: ["性能优化", "Web前端", "工程化"],
    authorNickname: "极速开发者",
    authorAvatar: "",
    likeCount: 84120,
    favoriteCount: 26500,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193284",
    title: "跨考自学编程三年收获心仪 Offer 的成长心路",
    description: "非科班转码三年，如何在知光社区找到同频伙伴、建立成瘾式自学反馈回路的心得总结。",
    coverImage: "https://images.unsplash.com/photo-1522202176988-66273c2fd55f?w=600&auto=format&fit=crop&q=80",
    tags: ["职场成长", "求职经历", "自学心得"],
    authorNickname: "逆风奔跑的少年",
    authorAvatar: "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=100&auto=format&fit=crop&q=80",
    likeCount: 91400,
    favoriteCount: 28400,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193285",
    title: "【知光精选】Prompt 工程系统方法论与模版库",
    description: "结构化提示词编写规范：清晰定义角色、任务背景、输入输出格式与 Few-Shot 边界约束。",
    tags: ["Prompt", "大模型", "AI写作"],
    authorNickname: "知光用户4da460e5",
    authorAvatar: "",
    likeCount: 63890,
    favoriteCount: 17200,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193286",
    title: "深入浅出 Docker 容器底层隔离技术与内核原理解析",
    description: "Linux Cgroups 资源限制、Namespace 命名空间隔离与 OverlayFS 联合文件系统底层剖析。",
    tags: ["Docker", "Linux", "容器技术"],
    authorNickname: "内核极客",
    authorAvatar: "",
    likeCount: 27500,
    favoriteCount: 6980,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193287",
    title: "程序员的极简高效生产力桌面环境搭建分享",
    description: "双屏显示支架、人体工学配置、无纸化笔记与沉浸式快捷键工作流指南。",
    coverImage: "https://images.unsplash.com/photo-1517694712202-14dd9538aa97?w=600&auto=format&fit=crop&q=80",
    tags: ["桌面美学", "生产力", "开发者生活"],
    authorNickname: "极客阿星",
    authorAvatar: "https://images.unsplash.com/photo-1472099645785-5658abf4ff4e?w=100&auto=format&fit=crop&q=80",
    likeCount: 45600,
    favoriteCount: 13900,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193288",
    title: "Next.js 14 App Router 生产架构演进与缓存体系",
    description: "Server Components 性能边界、Streaming SSR 渐进渲染、动态路由 Data Cache 避坑总结。",
    tags: ["Nextjs", "React", "全栈开发"],
    authorNickname: "全栈漫游者",
    authorAvatar: "",
    likeCount: 38200,
    favoriteCount: 11200,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193289",
    title: "计算机视觉 (CV) 目标检测 YOLO 系列算法全解读",
    description: "从 YOLOv1 经典回归架构到 YOLOv8/v9 特征金字塔与无锚框检测 (Anchor-Free) 全流程剖析。",
    coverImage: "https://images.unsplash.com/photo-1526374965328-7f61d4dc18c5?w=600&auto=format&fit=crop&q=80",
    tags: ["计算机视觉", "YOLO", "深度学习"],
    authorNickname: "视觉工程师小李",
    authorAvatar: "",
    likeCount: 41200,
    favoriteCount: 12900,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193290",
    title: "Go 语言高并发并发原语与 GMP 调度模型深度解析",
    description: "Goroutine 栈伸缩、Channel 底层实现、Work Stealing 任务窃取与系统调用网络轮询器机制。",
    tags: ["Golang", "并发编程", "后端开发"],
    authorNickname: "Gopher探秘",
    authorAvatar: "",
    likeCount: 33400,
    favoriteCount: 9800,
    liked: false,
    faved: false,
    visible: "public"
  },
  {
    id: "102003327239193291",
    title: "如何构建个人第二大脑：Obsidian 知识管理系统实战",
    description: "基于双链笔记与卡片盒笔记法 (Zettelkasten)，打造随时间自然生长的个人网络化知识资产库。",
    coverImage: "https://images.unsplash.com/photo-1512820790803-83ca734da794?w=600&auto=format&fit=crop&q=80",
    tags: ["知识管理", "Obsidian", "第二大脑", "生产力"],
    authorNickname: "思维工匠",
    authorAvatar: "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=100&auto=format&fit=crop&q=80",
    likeCount: 56700,
    favoriteCount: 21300,
    liked: true,
    faved: true,
    visible: "public"
  },
  {
    id: "102003327239193292",
    title: "Redis 深度进阶：从单线程事件模型到集群一致性方案",
    description: "彻底弄懂 Redis 为什么这么快？IO 多路复用、跳表实现、AOF 重写策略与 Redis Cluster 槽位迁移机制。",
    tags: ["Redis", "缓存架构", "高并发", "分布式"],
    authorNickname: "知光用户8f6544db",
    authorAvatar: "",
    likeCount: 68900,
    favoriteCount: 23100,
    liked: false,
    faved: false,
    visible: "public"
  }
];

// 本地可变状态缓存，支持点赞、收藏在页面上实时交互变更
const mockStateMap = new Map<string, { likeCount: number; favCount: number; liked: boolean; faved: boolean }>();

const ensureMockState = (item: FeedItem) => {
  if (!mockStateMap.has(item.id)) {
    mockStateMap.set(item.id, {
      likeCount: item.likeCount ?? 0,
      favCount: item.favoriteCount ?? 0,
      liked: Boolean(item.liked),
      faved: Boolean(item.faved)
    });
  }
  const s = mockStateMap.get(item.id)!;
  return {
    ...item,
    likeCount: s.likeCount,
    favoriteCount: s.favCount,
    liked: s.liked,
    faved: s.faved
  };
};

export const getMockFeed = (page = 1, size = 20) => {
  const all = MOCK_FEED_ITEMS.map(ensureMockState);
  const start = (page - 1) * size;
  const end = start + size;
  const items = all.slice(start, end);
  const hasMore = end < all.length;
  return {
    items,
    page,
    size,
    hasMore
  };
};

export const getMockDetail = (id: string): KnowpostDetailResponse => {
  const found = MOCK_FEED_ITEMS.find(item => item.id === id) ?? MOCK_FEED_ITEMS[0];
  const state = ensureMockState(found);
  return {
    id: state.id,
    title: state.title,
    description: state.description,
    contentUrl: `/mock-content/${state.id}`,
    images: state.coverImage ? [state.coverImage] : [],
    tags: state.tags,
    authorAvatar: state.authorAvatar,
    authorNickname: state.authorNickname,
    authorId: 1001,
    likeCount: state.likeCount ?? 0,
    favoriteCount: state.favoriteCount ?? 0,
    liked: state.liked,
    faved: state.faved,
    isTop: Boolean(state.isTop),
    visible: "public",
    type: "image_text",
    publishTime: "2026-11-09 12:00:00"
  };
};

export const getMockContentMarkdown = (id: string): string => {
  const found = MOCK_FEED_ITEMS.find(item => item.id === id) ?? MOCK_FEED_ITEMS[0];
  return `# ${found.title}

> ${found.description}

## 一、概述与核心背景

在当今人工智能与大模型技术迅速演进的背景下，如何让技术切实落地并解决生产中的复杂问题，成为了各团队关注的核心命题。

知光社区旨在为广大技术探索者搭建一个温润、专注、富有营养的知识空间。

\`\`\`python
# 示例代码：向量召回与增强检索流程
def retrieve_and_generate(query: str, top_k: int = 5):
    query_vector = embed(query)
    relevant_docs = vector_db.search(query_vector, top_k=top_k)
    prompt = build_context_prompt(query, relevant_docs)
    return llm.generate(prompt)
\`\`\`

## 二、架构要点对比

1. **分块策略 (Chunking)**：合理设定 Overlap 步长，保持语义连续性；
2. **多路召回 (Hybrid Search)**：结合 BM25 稀疏检索与 Dense 向量检索；
3. **重排序 (Re-ranking)**：通过交叉注意力模型重新评估相关度；
4. **幻觉缓解**：引入严格上下文溯源机制。

---
*知光社区 · 知识因分享而更有光芒*
`;
};

export const toggleMockLike = (id: string, like: boolean) => {
  const s = mockStateMap.get(id) ?? { likeCount: 100, favCount: 50, liked: false, faved: false };
  if (like && !s.liked) {
    s.liked = true;
    s.likeCount += 1;
  } else if (!like && s.liked) {
    s.liked = false;
    s.likeCount = Math.max(0, s.likeCount - 1);
  }
  mockStateMap.set(id, s);
  return { changed: true, liked: s.liked };
};

export const toggleMockFav = (id: string, fav: boolean) => {
  const s = mockStateMap.get(id) ?? { likeCount: 100, favCount: 50, liked: false, faved: false };
  if (fav && !s.faved) {
    s.faved = true;
    s.favCount += 1;
  } else if (!fav && s.faved) {
    s.faved = false;
    s.favCount = Math.max(0, s.favCount - 1);
  }
  mockStateMap.set(id, s);
  return { changed: true, faved: s.faved };
};

export const getMockCounters = (id: string) => {
  const s = mockStateMap.get(id) ?? { likeCount: 100, favCount: 50, liked: false, faved: false };
  return {
    entityType: "knowpost",
    entityId: id,
    counts: {
      like: s.likeCount,
      fav: s.favCount
    }
  };
};

export const searchMockItems = (q: string, tags?: string) => {
  const keyword = q.trim().toLowerCase();
  const all = MOCK_FEED_ITEMS.map(ensureMockState);
  const filtered = all.filter(item => {
    const titleMatch = item.title.toLowerCase().includes(keyword);
    const descMatch = item.description.toLowerCase().includes(keyword);
    const tagMatch = item.tags.some(t => t.toLowerCase().includes(keyword));
    const specificTagMatch = tags ? item.tags.some(t => tags.includes(t)) : true;
    return (titleMatch || descMatch || tagMatch) && specificTagMatch;
  });
  return {
    items: filtered,
    total: filtered.length,
    hasMore: false
  };
};
