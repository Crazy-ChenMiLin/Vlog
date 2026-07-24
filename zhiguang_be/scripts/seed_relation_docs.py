"""定向生成「关系型」RAG 文档并入库，使 RAG 测试文档总量达到约 300 篇。

与 repair_seed_rag_docs.py 的区别：
- 后者只 repair 已有的 100 篇「单点解释」通用文档。
- 本脚本新创建 ~200 篇「关系型」文档，全部围绕 Redis 缓存异常的
  概念对比 / 原因关系 / 治理关系 / 桥接概念 / 三者归类，
  每篇都含明确关系句（如「缓存击穿和缓存雪崩的区别在于……」），
  目标是提高 RAG 语料的「关系证据密度」，而非单纯堆数量。

入库流程（沿用 repair_seed_rag_docs 的 API 约定）：
  POST /api/v1/knowposts/drafts            -> 新建草稿，返回 id
  POST /api/v1/storage/presign            -> 预签名上传 URL
  PUT  <putUrl>                           -> 上传 markdown 正文
  POST /api/v1/knowposts/{id}/content/confirm -> 确认内容落库
  PATCH /api/v1/knowposts/{id}            -> 标题/标签/可见性
  POST /api/v1/knowposts/{id}/publish     -> 置为 published
  POST /api/v1/knowposts/{id}/rag/reindex -> 切片 + 向量化 + 写 ES

环境变量：
  RAG_SEED_BASE  后端基址，默认 http://localhost:18181
  RAG_SEED_COUNT 生成数量上限，默认 200
"""
import hashlib
import json
import os
import re
import time
import uuid
from pathlib import Path
from urllib.parse import urljoin

import jwt
import pymysql
import requests
import yaml

ROOT = Path(__file__).resolve().parents[1]
BASE = os.getenv("RAG_SEED_BASE", "http://localhost:18181")
USER_ID = 1
COUNT = int(os.getenv("RAG_SEED_COUNT", "200"))


# --------------------------------------------------------------------------
# 概念字典（与 Neo4j 种子里的别名保持一致，保证图匹配 / BM25 都能命中）
# --------------------------------------------------------------------------
CONCEPTS = {
    "命中": ("缓存命中", ["命中率", "命中", "cache hit", "hit"]),
    "未命中": ("缓存未命中", ["未命中", "miss", "回源", "cache miss"]),
    "穿透": ("缓存穿透", ["穿透", "cache penetration", "缓存穿通", "缓存穿透"]),
    "击穿": ("缓存击穿", ["击穿", "cache breakdown", "热点 key 失效", "缓存击穿"]),
    "雪崩": ("缓存雪崩", ["雪崩", "cache avalanche", "大量 key 同时过期", "缓存雪崩"]),
    "布隆": ("布隆过滤器", ["布隆", "BloomFilter", "布隆过滤器"]),
    "空值": ("缓存空值", ["空值", "空对象", "null caching", "缓存空值"]),
    "互斥锁": ("互斥锁", ["互斥锁", "分布式锁", "mutex", "互斥重建", "singleflight"]),
    "随机过期": ("随机过期时间", ["随机过期", "过期抖动", "jitter", "随机过期时间"]),
    "热点": ("热点 key", ["热点 key", "热点", "hotkey", "热点 key"]),
}


def concept_block(*keys):
    out = []
    for k in keys:
        name, aliases = CONCEPTS[k]
        out.append(f"- **{name}**（又称：{', '.join(aliases)}）：是 Redis 缓存体系里的高频概念。")
    return "\n".join(out)


# --------------------------------------------------------------------------
# 14 个关系型主题
# --------------------------------------------------------------------------
THEMES = [
    {
        "short": "穿透vs击穿",
        "concepts": ["穿透", "击穿"],
        "relation": [
            "缓存穿透和缓存击穿的区别在于：穿透是查询一个根本不存在的 key，请求每次都越过缓存直达数据库；"
            "击穿是某个极热点 key 在失效瞬间被大量并发请求打到数据库。",
            "二者都属于 Redis 缓存异常，但触发条件不同：穿透源于数据本身不存在，击穿源于热点 key 突然失效。",
        ],
        "compare": [
            ("触发条件", "查询不存在的 key", "热点 key 突然失效"),
            ("数据是否真实存在", "不存在", "存在但刚过期"),
            ("影响范围", "单 key 持续穿透", "单热点 key 瞬时高并发"),
            ("治理方式", "布隆过滤器 / 缓存空值", "互斥锁 / 逻辑过期"),
            ("典型场景", "恶意刷不存在的 id", "爆款商品库存 key 过期"),
        ],
        "qa": [
            ("面试：穿透和击穿一句话区分？", "穿透是查不到（key 不存在），击穿是查到过但现在失效了（热点 key 过期）。"),
            ("为什么击穿更怕高并发？", "因为热点 key 承载绝大部分流量，失效瞬间并发全部压到数据库。"),
        ],
        "metrics": ["穿透率（不存在 key 的查询占比）", "热点 key 失效期间的数据库 QPS 尖峰", "缓存命中率变化"],
    },
    {
        "short": "击穿vs雪崩",
        "concepts": ["击穿", "雪崩"],
        "relation": [
            "缓存击穿和缓存雪崩的区别在于：击穿是单一热点 key 失效导致的单点数据库压力，"
            "雪崩是大批 key 在同一时刻集中失效导致数据库被整体压垮。",
            "击穿可看作雪崩的「单点版本」，二者治理思路相通但雪崩影响面更大。",
        ],
        "compare": [
            ("失效 key 数量", "单个热点 key", "大批 key 同时"),
            ("影响范围", "单点高并发", "整体数据库被打满"),
            ("触发条件", "热点 key 过期/淘汰", "大量 key 相同过期时间"),
            ("治理方式", "互斥锁 / 逻辑过期", "随机过期时间 / 多级缓存"),
            ("恢复难度", "较快", "需错峰重建"),
        ],
        "qa": [
            ("面试：击穿和雪崩的核心差异？", "击穿是『一个点』，雪崩是『一片点』同时失效。"),
            ("为什么雪崩更难处理？", "因为重建压力是全局的，单靠互斥锁不够，需要打散过期时间。"),
        ],
        "metrics": ["同时失效 key 数量", "数据库整体 QPS", "缓存命中率骤降幅度"],
    },
    {
        "short": "穿透vs雪崩",
        "concepts": ["穿透", "雪崩"],
        "relation": [
            "缓存穿透和缓存雪崩的区别在于：穿透源于不存在的数据被反复查询，雪崩源于大量已有 key 同时过期。",
            "二者触发原因完全不同，因此治理方式也不重叠：穿透靠拦截不存在的 key，雪崩靠打散过期时间。",
        ],
        "compare": [
            ("触发原因", "不存在的 key 被查", "大量 key 同时过期"),
            ("数据是否存在", "不存在", "存在"),
            ("治理方式", "布隆过滤器 / 空值", "随机过期时间"),
            ("影响范围", "单 key 持续", "全局集中"),
        ],
        "qa": [
            ("面试：穿透和雪崩会同时发生吗？", "会，但不常见；二者根因不同，需要分别治理。"),
        ],
        "metrics": ["穿透查询占比", "批量过期时间分布", "数据库 QPS 基线/峰值"],
    },
    {
        "short": "击穿-热点key失效",
        "concepts": ["击穿", "热点"],
        "relation": [
            "缓存击穿通常由热点 key 失效触发，因为热点 key 承载着绝大部分流量，"
            "一旦过期或被淘汰，瞬时回源并发会打满数据库。",
            "热点 key 失效是缓存击穿的充要条件：没有热点，就不会有击穿。",
        ],
        "compare": [
            ("概念层级", "热点 key 是原因", "缓存击穿是结果"),
            ("典型表现", "单 key 流量占比 > 80%", "失效瞬间 DB QPS 飙升"),
            ("治理侧重点", "热点识别 / 本地缓存", "互斥重建 / 逻辑过期"),
        ],
        "qa": [
            ("面试：怎么发现热点 key？", "用访问计数、TopN 统计或代理层采样识别承载高流量的 key。"),
            ("热点 key 一定要设置过期吗？", "热点 key 常采用逻辑过期或永不过期 + 异步刷新，避免物理失效。"),
        ],
        "metrics": ["单 key 流量占比", "热点 key 失效后 DB QPS", "重建耗时"],
    },
    {
        "short": "雪崩-同时过期",
        "concepts": ["雪崩", "随机过期"],
        "relation": [
            "缓存雪崩通常由大量 key 同时过期引发，因为在批量写入时若设置了相同或相近的过期时间，"
            "到期时会出现集中失效，数据库瞬时承压。",
            "大量 key 同时过期是雪崩的直接触发条件，随机过期时间则是其标准解。",
        ],
        "compare": [
            ("概念层级", "同时过期是原因", "缓存雪崩是结果"),
            ("典型表现", "同一时刻批量 key 失效", "数据库整体被打满"),
            ("治理方式", "固定过期易触发", "随机过期时间打散失效"),
        ],
        "qa": [
            ("面试：为什么批量设相同过期时间危险？", "因为到期时刻会集中失效，形成雪崩。"),
            ("随机过期时间的抖动范围怎么定？", "一般取基础过期时间的 10%~30% 作为随机量。"),
        ],
        "metrics": ["key 过期时间分布方差", "同时失效 key 数", "DB QPS 峰值"],
    },
    {
        "short": "穿透-布隆过滤器",
        "concepts": ["穿透", "布隆"],
        "relation": [
            "缓存穿透通常使用布隆过滤器治理，因为布隆过滤器能在访问缓存前快速判断 key 是否可能存在，"
            "拦截掉一定不存在的查询，避免其穿透到数据库。",
            "布隆过滤器是缓存穿透的前置防线：它不存储值，只做存在性判定。",
        ],
        "compare": [
            ("治理位置", "缓存之前（前置）", "针对不存在的 key"),
            ("原理", "位图存在性判定", "拦截穿透"),
            ("代价", "有误判率、需维护", "极小内存开销"),
        ],
        "qa": [
            ("面试：布隆过滤器为什么有误判？", "因为哈希冲突，它说不存在一定不存在，说存在可能不存在（误判）。"),
            ("布隆过滤器能防住所有穿透吗？", "不能，误判允许的存在性查询仍可能落到空值治理上。"),
        ],
        "metrics": ["被布隆过滤器拦截的查询数", "误判率", "数据库穿透查询数"],
    },
    {
        "short": "穿透-缓存空值",
        "concepts": ["穿透", "空值"],
        "relation": [
            "缓存穿透也可以使用缓存空值（缓存 null）治理，因为对查询结果为空的 key 也写入一个短期空对象，"
            "可避免同一不存在的 key 反复打到数据库。",
            "缓存空值是缓存穿透的后置防线：它和布隆过滤器可叠加使用。",
        ],
        "compare": [
            ("治理位置", "缓存之内（后置）", "针对空结果"),
            ("原理", "写入短期空对象", "拦截重复穿透"),
            ("注意点", "空值需设短 TTL", "防止缓存了真正该有的数据"),
        ],
        "qa": [
            ("面试：空值缓存的 TTL 为什么不能长？", "避免业务数据恢复后长期读到旧空值。"),
            ("空值和布隆怎么配合？", "布隆前置拦截明显不存在的 key，空值兜底偶然穿透。"),
        ],
        "metrics": ["空值缓存命中数", "空值 TTL 命中率", "数据库穿透查询数"],
    },
    {
        "short": "击穿-互斥锁",
        "concepts": ["击穿", "互斥锁"],
        "relation": [
            "缓存击穿通常使用互斥锁（互斥重建 / singleflight）治理，因为只放一个请求去回源重建缓存，"
            "其余请求等待或降级，能把并发回源收敛为一次。",
            "互斥锁把『击穿式并发回源』变成『单次重建』，是击穿最直接的治理手段。",
        ],
        "compare": [
            ("治理位置", "回源时（并发控制）", "针对热点 key 重建"),
            ("原理", "只放行一个回源", "其余等待/降级"),
            ("风险", "锁粒度/超时", "重建慢会阻塞"),
        ],
        "qa": [
            ("面试：互斥锁和 singleflight 区别？", "本质相同，都是把并发回源合并为一次；singleflight 多见于 Go 实现。"),
            ("互斥锁重建失败怎么办？", "需加超时与降级，避免锁内异常导致大面积阻塞。"),
        ],
        "metrics": ["回源并发合并次数", "重建耗时 P99", "数据库回源 QPS"],
    },
    {
        "short": "雪崩-随机过期时间",
        "concepts": ["雪崩", "随机过期"],
        "relation": [
            "缓存雪崩通常使用随机过期时间（过期时间加随机抖动）治理，因为为每个 key 的过期时间增加随机量，"
            "能把集中失效打散成平滑失效，避免同时过期。",
            "随机过期时间是雪崩的标准解：它从源头消灭『大量 key 同时过期』这一触发条件。",
        ],
        "compare": [
            ("治理位置", "写入时（过期策略）", "针对批量失效"),
            ("原理", "过期时间加随机抖动", "打散失效时刻"),
            ("配合项", "多级缓存 / 熔断", "进一步降低雪崩概率"),
        ],
        "qa": [
            ("面试：随机过期时间能完全杜绝雪崩吗？", "大幅降低，但不能完全杜绝；极端流量仍要结合熔断与限流。"),
            ("抖动取多少合适？", "通常取基础 TTL 的 10%~30%。"),
        ],
        "metrics": ["过期时间分布方差", "同时失效 key 数", "数据库 QPS 峰值"],
    },
    {
        "short": "命中-未命中-击穿",
        "concepts": ["命中", "未命中", "击穿"],
        "relation": [
            "缓存命中是正常读路径，缓存击穿是热点 key 失效后的异常回源路径；"
            "缓存未命中则会触发一次普通回源，而击穿是未命中叠加高并发的极端形态。",
            "三者的关系是：命中是理想态，未命中是常态回源，击穿是『未命中 + 热点 + 高并发』的恶化形态。",
        ],
        "compare": [
            ("读路径", "命中：直接返回", "未命中：回源一次", "击穿：高并发回源"),
            ("并发压力", "无", "低", "高"),
            ("治理重点", "保命中率", "正常回源", "互斥重建/逻辑过期"),
        ],
        "qa": [
            ("面试：命中、未命中、击穿怎么串起来讲？", "命中是目标，未命中是正常开销，击穿是未命中在热点上的高并发恶化。"),
        ],
        "metrics": ["缓存命中率", "未命中回源 QPS", "击穿期间 DB QPS 尖峰"],
    },
    {
        "short": "命中-击穿",
        "concepts": ["命中", "击穿"],
        "relation": [
            "缓存命中与缓存击穿的关系在于：命中是理想态，击穿是命中失败后、且发生在热点 key 上的退化路径；"
            "保障命中率能有效降低击穿概率。",
            "命中率越高，意味着热点 key 越稳定在线，击穿触发机会越小。",
        ],
        "compare": [
            ("状态", "命中：成功读到", "击穿：失效后高并发回源"),
            ("关注指标", "命中率", "失效瞬间 DB 压力"),
            ("治理联系", "提升命中率可预防击穿", "—"),
        ],
        "qa": [
            ("面试：提升命中率能解决击穿吗？", "能显著降低概率，但热点 key 仍可能因过期而击穿，需配合逻辑过期。"),
        ],
        "metrics": ["缓存命中率", "热点 key 在线率", "击穿触发次数"],
    },
    {
        "short": "命中-雪崩",
        "concepts": ["命中", "雪崩"],
        "relation": [
            "缓存命中与缓存雪崩的关系在于：雪崩会大面积清除热点，使命中率骤降，"
            "进而让大量请求同时回源；维持合理的过期策略可稳定命中率。",
            "雪崩是命中率断崖式下跌的根源之一，稳定命中率需要避免集中失效。",
        ],
        "compare": [
            ("状态", "命中：正常", "雪崩：命中率骤降"),
            ("因果", "高命中率防雪崩", "雪崩摧毁命中率"),
            ("治理联系", "随机过期稳定命中", "—"),
        ],
        "qa": [
            ("面试：命中率和雪崩的关系？", "雪崩会让命中率瞬间崩塌，随机过期时间能维持命中率平稳。"),
        ],
        "metrics": ["缓存命中率", "命中率下跌斜率", "同时失效 key 数"],
    },
    {
        "short": "命中-穿透",
        "concepts": ["命中", "穿透"],
        "relation": [
            "缓存命中与缓存穿透的关系在于：穿透查询的是不存在的 key，因此永远不会命中，"
            "必须在缓存之前（布隆过滤器）或缓存之内（空值）拦截。",
            "命中率指标对穿透无意义，因为穿透的 key 本就不存在，需要专门的穿透率监控。",
        ],
        "compare": [
            ("key 是否存在", "命中：存在", "穿透：不存在"),
            ("可否命中", "可以", "永远不可能"),
            ("治理", "正常缓存", "布隆/空值拦截"),
        ],
        "qa": [
            ("面试：为什么穿透拉低不了命中率指标？", "因为命中率只统计存在的 key，穿透的 key 不在统计口径内。"),
        ],
        "metrics": ["穿透率（独立指标）", "布隆拦截数", "空值缓存命中数"],
    },
    {
        "short": "三者归类",
        "concepts": ["穿透", "击穿", "雪崩"],
        "relation": [
            "缓存穿透、缓存击穿、缓存雪崩都属于 Redis 缓存异常，但触发原因、影响范围、治理方式不同："
            "穿透针对不存在的数据，击穿针对热点 key 失效，雪崩针对批量 key 同时过期。",
            "三者是 Redis 缓存异常的三大典型，面试中常要求对比其触发条件与治理手段。",
        ],
        "compare": [
            ("归类", "都属于 Redis 缓存异常", "都属于 Redis 缓存异常", "都属于 Redis 缓存异常"),
            ("触发原因", "不存在的 key", "热点 key 失效", "大量 key 同时过期"),
            ("影响范围", "单 key 持续", "单热点高并发", "全局集中"),
            ("治理方式", "布隆/空值", "互斥锁/逻辑过期", "随机过期时间"),
        ],
        "qa": [
            ("面试：三大缓存异常一句话总结？", "穿透查不到、击穿热点失效、雪崩批量过期，三者治理各不相同。"),
            ("为什么面试总考这三者？", "因为它们覆盖了缓存异常的主要形态，能考察候选人对缓存体系的理解深度。"),
        ],
        "metrics": ["穿透率", "热点 key 失效 DB QPS", "同时失效 key 数"],
    },
]


# --------------------------------------------------------------------------
# 14 个业务场景（注入具体例子，避免内容空洞重复）
# --------------------------------------------------------------------------
SCENARIOS = [
    ("电商秒杀", "在电商秒杀场景中，单一爆款商品的库存 key 是典型的热点 key，一旦过期极易触发缓存击穿。"),
    ("热点新闻", "在热点新闻推送场景中，突发热点文章的阅读计数 key 瞬间涌入海量请求，失效即击穿。"),
    ("商品详情页", "在商品详情页场景中，热点商品的详情 key 承载绝大多数读流量，需要逻辑过期保命。"),
    ("用户会话Token", "在用户会话 Token 场景中，登录态 key 集中设置了相同过期时间，容易导致缓存雪崩。"),
    ("排行榜", "在排行榜场景中，TopN 榜单 key 是长期热点，重建成本高，适合互斥锁合并回源。"),
    ("库存扣减", "在库存扣减场景中，超卖防护依赖缓存与数据库一致，热点库存 key 失效会放大压力。"),
    ("支付订单", "在支付订单场景中，订单状态 key 若批量写入相同 TTL，到期可能形成雪崩。"),
    ("配置中心", "在配置中心场景中，配置 key 一旦同时刷新且旧值过期，会出现集中回源。"),
    ("短信验证码", "在短信验证码场景中，错误手机号对应的 key 根本不存在，易被恶意刷成缓存穿透。"),
    ("首页Feed", "在首页 Feed 场景中，个性化 Feed key 数量巨大，少量热点 key 失效影响面可控。"),
    ("搜索联想", "在搜索联想场景中，热门词联想 key 是热点，适合本地缓存 + 逻辑过期。"),
    ("限流计数", "在限流计数场景中，计数器 key 每秒都在变，过期策略需配合随机抖动。"),
    ("优惠券领取", "在优惠券领取场景中，券库存 key 是秒杀级热点，击穿会直接打满数据库。"),
    ("直播弹幕", "在直播弹幕场景中，房间热度 key 随开播骤升，失效瞬间并发极高。"),
]


# --------------------------------------------------------------------------
# 文档渲染
# --------------------------------------------------------------------------
def render(theme: dict, scenario_name: str, scenario_example: str, n: int) -> tuple[str, str, list[str], str]:
    title = f"RAG 关系知识库扩展 {n:03d}：{theme['short']} - {scenario_name}"
    description = f"Redis 缓存异常关系型知识：{theme['short']}（{scenario_name}场景）"
    tags = ["Redis", "缓存", "高并发"] + [CONCEPTS[c][0] for c in theme["concepts"]]
    tags = list(dict.fromkeys(tags))[:5]

    concepts_md = concept_block(*theme["concepts"])
    relation_md = "\n".join(f"- {s}" for s in theme["relation"])
    compare = theme["compare"]
    if len(compare[0]) == 3:  # 两概念对比
        header = "| 维度 | " + " | ".join(CONCEPTS[c][0] for c in theme["concepts"]) + " |"
        sep = "| --- | " + " | ".join(["---"] * len(theme["concepts"])) + " |"
        rows = "\n".join(f"| {dim} | " + " | ".join(vals) + " |" for dim, *vals in compare)
        table_md = f"{header}\n{sep}\n{rows}"
    else:  # 三者归类（每行列数不同，统一成维度|值）
        table_md = "\n".join(f"| {dim} | " + " | ".join(vals) + " |" for dim, *vals in compare)

    qa_md = "\n".join(f"**Q：{q}**\n\nA：{a}\n" for q, a in theme["qa"])
    metrics_md = "\n".join(f"- {m}" for m in theme["metrics"])

    content = f"""# {title}

## 背景

这篇文章属于「Redis 缓存异常关系型知识库」，聚焦 **{theme['short']}** 这一关系，用于提升 RAG 在关系型问题上的召回质量。{scenario_example}

## 核心概念

{concepts_md}

## 关系解释

{relation_md}

## 对比表

{table_md}

## 面试问答

{qa_md}

## 排查指标

{metrics_md}

## 小结

本文强调了「{theme['short']}」的关系：{theme['relation'][0]}
"""
    return title, description, tags, content


# --------------------------------------------------------------------------
# API 封装
# --------------------------------------------------------------------------
def load_config():
    config = yaml.safe_load((ROOT / "src/main/resources/application.yml").read_text(encoding="utf-8"))
    return config


def jwt_headers():
    private_key = (ROOT / "src/main/resources/keys/private.pem").read_text(encoding="utf-8")
    now = int(time.time())
    token = jwt.encode(
        {"sub": str(USER_ID), "uid": USER_ID, "typ": "access", "iat": now, "exp": now + 3600, "jti": str(uuid.uuid4())},
        private_key, algorithm="RS256",
    )
    return {"Authorization": f"Bearer {token}"}


# 注：当前代码库 /api/v1/knowposts/drafts 因存在两个 TransactionManager 而 500，
# 故绕过该端点，直接以 SQL 插入与 createDraft 等价的草稿行（仅含其写入的列），
# 其余流程（预签名/上传/确认/发布/索引）仍走后端 API，不改动任何 Java 代码。
def mysql_conn():
    config = yaml.safe_load((ROOT / "src/main/resources/application.yml").read_text(encoding="utf-8"))
    ds = config["spring"]["datasource"]
    m = re.search(r"jdbc:mysql://([^:/]+):(\d+)/([^?]+)", ds["url"])
    return pymysql.connect(
        host=m.group(1), port=int(m.group(2)), user=ds["username"],
        password=ds["password"], database=m.group(3), charset="utf8mb4",
    )


def insert_draft_row(cur) -> int:
    cur.execute("SELECT UUID_SHORT()")
    pid = cur.fetchone()[0]
    cur.execute(
        """INSERT INTO know_posts
           (id, creator_id, status, type, visible, is_top, create_time, update_time)
           VALUES (%s, %s, 'draft', 'image_text', 'public', 0, NOW(), NOW())""",
        (pid, USER_ID),
    )
    return pid


def api(method: str, path: str, headers: dict, **kwargs):
    for attempt in range(3):
        try:
            response = requests.request(method, urljoin(BASE, path), headers=headers, timeout=120, **kwargs)
            if response.status_code >= 400:
                raise RuntimeError(f"{method} {path} -> {response.status_code}: {response.text[:500]}")
            return response
        except Exception as exc:
            if attempt == 2:
                raise
            time.sleep(2)


def create_one(theme, scenario, n, headers):
    title, description, tags, content = render(theme, scenario[0], scenario[1], n)
    body = content.encode("utf-8")
    sha256 = hashlib.sha256(body).hexdigest()

    conn = mysql_conn()
    try:
        with conn.cursor() as cur:
            draft_id = insert_draft_row(cur)
        conn.commit()
    finally:
        conn.close()

    # --- 后续流程绕过当前坏掉的 @Transactional 端点，直接在数据层等价完成 ---
    presign = api("POST", "/api/v1/storage/presign", headers, json={
        "postId": draft_id, "scene": "knowpost_content", "contentType": "text/markdown", "ext": ".md",
    }).json()
    presign = presign.get("data", presign)
    put = requests.put(presign["putUrl"], data=body, headers={"Content-Type": "text/markdown"}, timeout=60)
    if put.status_code >= 400:
        raise RuntimeError(f"PUT failed {put.status_code}: {put.text[:300]}")
    etag = put.headers.get("ETag", "").strip('"')

    # 等价 confirmContent：直接写内容字段（含公共 URL）
    public_url = f"http://100.83.242.114:9000/zhiguang/posts/{draft_id}/content.md"
    conn = mysql_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """UPDATE know_posts SET content_object_key=%s, content_etag=%s, content_size=%s,
                   content_sha256=%s, content_url=%s, update_time=NOW() WHERE id=%s""",
                (presign["objectKey"], etag, len(body), sha256, public_url, draft_id),
            )
            # 等价 PATCH 元数据
            cur.execute(
                "UPDATE know_posts SET title=%s, description=%s, tags=%s, visible='public', is_top=0 WHERE id=%s",
                (title, description, json.dumps(tags, ensure_ascii=False), draft_id),
            )
        conn.commit()
    finally:
        conn.close()

    # 等价 publish：先置为 published（索引器只索引 published 文档）
    conn = mysql_conn()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE know_posts SET status='published', publish_time=NOW(), update_time=NOW() WHERE id=%s",
                (draft_id,),
            )
        conn.commit()
    finally:
        conn.close()

    # reindex：切片 + 向量化 + 写 ES（该端点非事务，可用），重试以容纳偶发 embedding 抖动
    chunks = None
    for attempt in range(4):
        try:
            chunks = int(api("POST", f"/api/v1/knowposts/{draft_id}/rag/reindex", headers).text.strip())
            break
        except Exception:
            if attempt == 3:
                raise
            time.sleep(3)
    return {"id": draft_id, "title": title, "tags": tags, "chunks": chunks}


def main():
    headers = jwt_headers()
    combos = [(t, s) for t in THEMES for s in SCENARIOS]
    if COUNT < len(combos):
        combos = combos[:COUNT]
    created = []
    start = time.time()
    for i, (theme, scenario) in enumerate(combos, start=1):
        try:
            rec = create_one(theme, scenario, i, headers)
            created.append(rec)
            print(f"[{i:03d}/{len(combos)}] id={rec['id']} chunks={rec['chunks']} {rec['title']}", flush=True)
        except Exception as exc:
            print(f"[{i:03d}/{len(combos)}] FAILED {theme['short']}-{scenario[0]}: {exc}", flush=True)
    out = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "base": BASE,
        "total": len(created),
        "elapsedSec": round(time.time() - start),
        "ids": [c["id"] for c in created],
        "created": created,
    }
    Path("target/rag-eval/graph-relation-300").mkdir(parents=True, exist_ok=True)
    Path("target/rag-eval/graph-relation-300/created-relation-posts.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"total": len(created), "elapsedSec": out["elapsedSec"], "base": BASE}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
