"""把「知光知识社区」的已发布文章补齐到目标数量（默认 1000）。

做法（与 seed_relation_docs.py 一致的旁路思路，但不依赖本地后端）：
  1. 直接 SQL 插入草稿行（id 用 UUID_SHORT()，creator 在已有用户间轮转）；
  2. 用 boto3 直连 MinIO 上传 markdown 正文到 posts/{id}/content.md；
  3. 一次 SQL UPDATE 写回内容字段 + 元数据 + 置为 published。

不调用 /drafts、/content/confirm、/publish 等事务端点，因此本地后端
无需启动；内容直接落 MinIO，详情页可正常渲染。RAG 向量索引（reindex）
不在本脚本内，需要时可另跑 reindex 脚本。

环境变量：
  SEED_TARGET   目标「已发布+公开」文章数，默认 1000
  SEED_STATE   进度记录文件，默认 target/seed1000/state.json
"""
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import pymysql
import boto3
from botocore.config import Config

ROOT = Path(__file__).resolve().parents[1]
TARGET = int(os.getenv("SEED_TARGET", "1000"))
STATE_FILE = Path(os.getenv("SEED_STATE", "target/seed1000/state.json"))
STATE_FILE.parent.mkdir(parents=True, exist_ok=True)

MYSQL = dict(host="100.83.242.114", port=3306, user="root", password="czqCZQ197623@",
             database="zhiguang_auth", charset="utf8mb4", autocommit=False)
MINIO = dict(endpoint_url="http://100.83.242.114:9000", aws_access_key_id="minio_fjTXH3",
             aws_secret_access_key="czqCZQ197623", region_name="us-east-1")
BUCKET = "zhiguang"
PUBLIC_DOMAIN = "http://100.83.242.114:9000"
TITLE_PREFIX = "【知光精选】"
CREATORS = [1, 2, 3, 4, 5]


# --------------------------------------------------------------------------
# 话题分类（family -> (base_tags, topics[{name, essence}], examples[])）
# --------------------------------------------------------------------------
FAMILIES = [
    ("Redis缓存", ["Redis", "缓存", "高并发"], [
        ("缓存穿透", "查询不存在的 key 反复打到数据库，需用布隆过滤器或空值缓存拦截。"),
        ("缓存击穿", "热点 key 失效瞬间高并发回源，用互斥锁或逻辑过期防护。"),
        ("缓存雪崩", "大量 key 同时过期压垮数据库，靠随机过期与多级缓存缓解。"),
        ("缓存一致性", "缓存与数据库双写易不一致，常用延迟双删或 Canal 订阅 binlog。"),
        ("布隆过滤器", "用位图做存在性判定，是缓存穿透的前置防线，有误判率。"),
        ("延迟双删", "写库前后各删一次缓存，降低双写不一致窗口。"),
        ("热点key", "承载绝大部分流量的 key，失效即击穿，需本地缓存或逻辑过期。"),
        ("多级缓存", "本地+分布式+CDN 分层，减少回源并抗突发流量。"),
        ("缓存预热", "上线前把热点数据提前加载进缓存，避免冷启动击穿。"),
    ], ["电商秒杀的库存 key 是典型热点，失效即击穿。", "用户会话 Token 集中过期容易引发缓存雪崩。", "短信验证码的错误手机号常被恶意刷成缓存穿透。"]),

    ("MySQL数据库", ["MySQL", "数据库", "索引"], [
        ("B+Tree索引", "多路平衡树降低树高、减少 IO，是 InnoDB 的默认索引结构。"),
        ("事务隔离级别", "读未提交到串行化，权衡一致性与并发，常用可重复读。"),
        ("MVCC", "多版本并发控制通过 undo log 与隐藏事务 id 实现快照读。"),
        ("慢查询优化", "用 EXPLAIN 看执行计划，重点优化全表扫描与临时表。"),
        ("联合索引", "最左前缀原则决定命中，区分度高的列放前面。"),
        ("覆盖索引", "查询字段都在索引里可避免回表，显著提升性能。"),
        ("主从复制", "基于 binlog 异步同步，用于读写分离与容灾。"),
        ("分库分表", "单表过大时按规则拆分，缓解写入与存储瓶颈。"),
        ("死锁排查", "通过 InnoDB 死锁日志定位循环等待，调整加锁顺序。"),
    ], ["订单表随业务增长到亿级需要分表。", "一条慢 SQL 就能拖垮整个数据库实例，需紧急优化。", "联合索引顺序写错会导致全表扫描。"]),

    ("消息队列Kafka", ["Kafka", "消息队列", "异步"], [
        ("消费者组", "同组消费者分摊分区，实现水平扩展与容错。"),
        ("分区顺序", "单分区内有序，跨分区不保证，需按 key 路由保序。"),
        ("幂等生产者", "启用幂等避免网络重试导致的消息重复。"),
        ("事务消息", "保证本地事务与消息发送的原子性，用于跨系统一致。"),
        ("死信队列", "消费多次失败的消息转入死信，便于人工兜底。"),
        ("再均衡", "消费者增减触发分区重分配，期间会短暂停止消费。"),
        ("削峰填谷", "用队列缓冲突发流量，保护下游不被打垮。"),
        ("消息积压", "消费慢导致堆积，需扩容消费者或提升处理吞吐。"),
        ("精确一次", "幂等+事务实现端到端 Exactly-Once 语义。"),
    ], ["订单创建后异步通知多个下游系统。", "消费失败的消息进入死信队列等待排查。", "大促流量用消息队列削峰保护订单服务。"]),

    ("SpringBoot", ["Spring", "后端", "Java"], [
        ("自动配置", "基于 classpath 与条件注解按需装配 Bean，减少样板配置。"),
        ("Bean生命周期", "从实例化到销毁经历多种回调，理解它有助于排错。"),
        ("事务传播", "多个方法嵌套调用时，传播行为决定事务如何复用或新建。"),
        ("AOP", "面向切面编程统一处理日志、鉴权、事务等横切关注点。"),
        ("异常处理", "@ControllerAdvice 全局捕获异常，返回统一错误结构。"),
        ("参数校验", "用 JSR-303 注解在入口校验，减少业务层判空。"),
        ("配置绑定", "@ConfigurationProperties 把配置映射成类型安全对象。"),
        ("启动优化", "排除无用自动配置、懒加载可缩短 SpringBoot 启动时间。"),
        ("优雅停机", "关闭时先停止接收新请求并等待在途请求完成。"),
    ], ["接口偶发 500 需要定位 Bean 注入问题。", "事务不回滚要先查传播行为。", "全局异常处理统一错误返回格式。"]),

    ("微服务", ["微服务", "架构", "分布式"], [
        ("服务注册发现", "服务启动时注册、调用时发现，解耦 Provider 与 Consumer。"),
        ("熔断", "依赖持续失败时快速失败，避免雪崩式连锁故障。"),
        ("限流", "控制单位时间请求量，保护系统不被突发流量冲垮。"),
        ("降级", "故障时返回兜底结果，保证核心链路可用。"),
        ("链路追踪", "用 TraceId 串起跨服务调用，定位慢请求与异常。"),
        ("配置中心", "配置集中管理并动态推送，避免改配置重启服务。"),
        ("网关", "统一鉴权、限流与路由，是微服务的入口防线。"),
        ("分布式事务", "跨服务数据一致用 TCC、Saga 或事务消息保证。"),
        ("幂等设计", "相同请求重复提交产生同一结果，防止重复扣款等。"),
    ], ["某服务宕机引发雪崩，需要熔断保护。", "配置变更要动态生效，不能重启服务。", "跨服务下单要保证最终一致。"]),

    ("系统设计", ["架构", "设计", "高可用"], [
        ("高可用", "通过冗余与故障转移消除单点，保障服务持续可用。"),
        ("高并发", "用缓存、异步与无状态水平扩展扛住大量并发。"),
        ("读写分离", "主库写、从库读，缓解单机数据库压力。"),
        ("分库分表", "数据量过大时拆分，突破单库存储与连接瓶颈。"),
        ("接口防刷", "用令牌桶、验证码与风控拦截恶意刷接口。"),
        ("任务调度", "用分布式调度框架保证定时任务不重复执行。"),
        ("灰度发布", "先放小流量验证，再全量，降低上线风险。"),
        ("限流算法", "令牌桶与漏桶各有侧重，分别平滑限流与恒定速率。"),
        ("缓存设计", "读写穿透/旁路/写回模式决定缓存与存储的交互方式。"),
    ], ["高并发抢购要限流防止超卖。", "读写分离缓解主库压力。", "灰度发布先验证新功能再全量。"]),

    ("分布式", ["分布式", "一致性", "算法"], [
        ("CAP", "分区下只能在一致性与可用性间取舍，工程常选 AP+最终一致。"),
        ("一致性哈希", "节点变化时仅少量 key 迁移，用于缓存与分片路由。"),
        ("分布式锁", "用 Redis/ZK 保证跨进程互斥，注意锁续期与误删。"),
        ("雪花算法", "时间戳+机器id+序列生成趋势递增唯一 id。"),
        ("选举", "主节点宕机后集群重新选主，保证只有一个领导者。"),
        ("Quorum", "读写多数派达成，平衡一致性与可用性。"),
        ("时钟漂移", "多机时钟不一致会导致排序错乱，用 NTP 或逻辑时钟。"),
        ("脑裂", "网络分区出现双主，需 fencing 机制防止双写。"),
        ("租约", "带期限的授权，过期自动失效，常用于选主与锁。"),
    ], ["缓存节点扩缩容用一致性哈希减少迁移。", "分布式锁防止任务被重复处理。", "选主失败出现脑裂，需要 fencing 机制。"]),

    ("前端React", ["前端", "React", "工程化"], [
        ("组件拆分", "按职责与复用边界拆组件，提升可维护性。"),
        ("状态管理", "全局状态用 Redux/Zustand，局部用 useState。"),
        ("请求封装", "统一拦截器处理 token、错误与 loading。"),
        ("路由守卫", "在进入路由前校验登录与权限。"),
        ("虚拟列表", "只渲染可视区域，支撑超长列表流畅滚动。"),
        ("错误边界", "捕获渲染异常，避免整页白屏。"),
        ("性能优化", "memo、useMemo、懒加载减少不必要的重渲染。"),
        ("Hooks", "用 useEffect/useRef 管理副作用与可变引用。"),
        ("受控非受控", "表单值由 React 控制还是 DOM 控制，各有适用场景。"),
    ], ["长列表卡顿，用虚拟列表优化。", "路由跳转前校验登录态。", "组件频繁重渲染需要 memo 优化。"]),

    ("前端工程化", ["前端", "工程化", "构建"], [
        ("打包优化", "拆包、压缩与 TreeShaking 减小产物体积。"),
        ("微前端", "多团队独立开发部署，运行时集成到同一页面。"),
        ("Monorepo", "多包单仓管理，共享依赖与工具链。"),
        ("CI/CD", "提交即自动构建测试部署，缩短交付周期。"),
        ("代码分割", "按需加载路由级 chunk，提升首屏速度。"),
        ("TreeShaking", "剔除未引用的导出，减小打包体积。"),
        ("SourceMap", "线上报错映射回源码，便于定位问题。"),
        ("构建缓存", "缓存产物与依赖，加速二次构建。"),
    ], ["首屏慢，做代码分割优化。", "多团队协作用微前端隔离。", "CI 自动部署提升交付效率。"]),

    ("算法", ["算法", "刷题", "数据结构"], [
        ("二分查找", "在有序区间每次折半，O(log n) 定位目标。"),
        ("动态规划", "用状态转移与最优子结构避免重复计算。"),
        ("贪心", "每步取局部最优，需证明能得全局最优。"),
        ("双指针", "首尾或快慢指针在有序结构里高效扫描。"),
        ("滑动窗口", "维护窗口内状态，求子串/子数组最优解。"),
        ("回溯", "试错式搜索所有解，配合剪枝减少规模。"),
        ("并查集", "维护连通性，用于集合合并与环检测。"),
        ("堆", "优先队列，O(1) 取最值，用于 TopK 与调度。"),
        ("拓扑排序", "依依赖顺序排任务，检测有向图环。"),
    ], ["求第 K 大元素用堆。", "子数组最大和用动态规划。", "字符串匹配用双指针。"]),

    ("数据结构", ["数据结构", "基础", "算法"], [
        ("红黑树", "近似平衡二叉搜索树，保证插入删除 O(log n)。"),
        ("跳表", "用多层索引加速查找，Redis ZSet 的底层之一。"),
        ("哈希表", "哈希函数映射 key，平均 O(1) 读写，需处理冲突。"),
        ("B+树", "多路平衡、叶子链表，适合磁盘存储的索引。"),
        ("LSM树", "顺序写+后台合并，适配高写吞吐的 KV 存储。"),
        ("堆", "完全二叉树实现优先队列，支持 TopK。"),
        ("队列", "先进先出，用于缓冲与广度优先搜索。"),
        ("栈", "后进先出，用于函数调用与表达式求值。"),
        ("图", "邻接表/矩阵表达关系，支撑最短路与连通性分析。"),
    ], ["排行榜用跳表实现。", "数据库索引用 B+树。", "缓存击穿计数用哈希表。"]),

    ("计算机网络", ["网络", "TCP", "HTTP"], [
        ("TCP三次握手", "双方确认收发能力，建立可靠连接。"),
        ("HTTP缓存", "强缓存与协商缓存减少重复传输。"),
        ("HTTPS", "TLS 握手协商密钥，保证传输加密与身份认证。"),
        ("拥塞控制", "慢启动与拥塞避免防止网络过载。"),
        ("粘包拆包", "TCP 字节流无边界，需定长/分隔符/长度头。"),
        ("DNS", "域名解析为 IP，递归与迭代查询结合。"),
        ("负载均衡", "把请求分摊到多实例，提升吞吐与可用。"),
        ("WebSocket", "全双工长连接，适合实时推送。"),
        ("QUIC", "基于 UDP 的可靠传输，0-RTT 建连降低延迟。"),
    ], ["HTTPS 抓包要看证书链。", "长连接推送用 WebSocket。", "首包慢要查 DNS 与握手。"]),

    ("操作系统", ["操作系统", "内核", "性能"], [
        ("进程线程", "进程资源隔离、线程共享地址空间，切换开销不同。"),
        ("死锁", "互斥、占有等待、不可剥夺、循环等待四条件同时成立。"),
        ("虚拟内存", "用页表把虚拟地址映射到物理页，突破内存限制。"),
        ("页表", "记录虚拟页到物理帧的映射，TLB 加速查表。"),
        ("上下文切换", "保存恢复寄存器状态，频繁切换会带来开销。"),
        ("IO多路复用", "epoll 单线程管大量连接，高并发网络基石。"),
        ("零拷贝", "减少内核态用户态间拷贝，提升文件传输效率。"),
        ("调度算法", "时间片轮转、CFS 等决定进程获得 CPU 的顺序。"),
    ], ["CPU 飙高要查上下文切换。", "内存不够看虚拟内存。", "高并发网络用 epoll。"]),

    ("AI大模型", ["AI", "大模型", "RAG"], [
        ("RAG", "检索增强生成先取相关文档再作答，缓解幻觉。"),
        ("向量数据库", "存 Embedding 并做相似检索，是 RAG 的记忆层。"),
        ("微调", "用领域数据继续训练，让模型适配特定任务。"),
        ("提示工程", "通过结构化 Prompt 引导模型输出更稳定。"),
        ("Agent", "让模型调用工具、规划步骤完成复杂任务。"),
        ("注意力机制", "自注意力让模型关注上下文中相关信息。"),
        ("量化", "降低权重精度压缩模型，兼顾速度与效果。"),
        ("蒸馏", "用小模型学大模型输出，迁移能力降本。"),
        ("多模态", "统一处理文本图像音频，扩展模型感知边界。"),
    ], ["内部问答用 RAG 接知识库。", "客服 Agent 调工具完成下单。", "边缘部署用小模型量化。"]),
]

VARIANTS = ["概念解析", "实战案例", "面试要点", "对比辨析", "排查手册", "最佳实践"]


# --------------------------------------------------------------------------
# 内容渲染
# --------------------------------------------------------------------------
def build_variant_body(family, topic, essence, example, variant, tags):
    title = f"{TITLE_PREFIX}{family} · {topic}（{variant}）"
    desc_src = f"{family} {topic} {variant}：{essence[:30]}"
    description = desc_src[:50]
    tags_json = json.dumps(list(dict.fromkeys([topic, family] + tags))[:5], ensure_ascii=False)

    if variant == "概念解析":
        body = f"""# {title}

## 背景

{essence} 在「{family}」领域里是被高频检索与讨论的知识点，也是知光社区沉淀实践经验的重点方向。

## 核心要点

- 理解 {topic} 时，先明确它要解决的问题与适用边界，而不是只背定义。
- 在 {family} 的实际工程里，{topic} 常出现在读多写少、高并发或异步链路中。
- 评估 {topic} 的效果时，要关注可观测指标，用数据说话而非凭感觉。
- 与 {topic} 易混淆的概念需要对比记忆，避免面试或排障时张冠李戴。

## 适用边界

{topic} 并非银弹：它在合适的规模与场景里收益最大，超出边界反而增加复杂度。落地前先确认业务是否真的需要。

## 小结

掌握 {topic} 的抓手是「定义 -> 场景 -> 风险 -> 方案 -> 观测」，把它放进 {family} 的整体脉络里理解，才不容易遗忘。
"""
    elif variant == "实战案例":
        body = f"""# {title}

## 背景

{topic} 不能只停留在理论。下面用一个真实可复现的场景，看它如何在 {family} 中落地。

## 场景示例

{example} 在这个场景里，{topic} 直接决定了系统的稳定性与吞吐。

## 落地步骤

1. 先量化现状：记录关键指标基线，确认 {topic} 是当前瓶颈。
2. 设计改动：把 {topic} 的相关逻辑抽成独立、可验证的模块。
3. 灰度验证：小流量先上，观察指标与异常日志。
4. 全量推广：确认无回归后再扩大范围，并补充监控。

## 常见坑

- 只看单接口返回，忽略整体链路，导致改了 {topic} 却没解决根因。
- 缺少回滚预案，一旦异常难以快速恢复。

## 小结

{topic} 的实战价值在于「先度量、再改动、可回滚」，这也是 {family} 工程化的基本纪律。
"""
    elif variant == "面试要点":
        body = f"""# {title}

## 背景

{topic} 是 {family} 面试中的高频考点，考察的不仅是记忆，更是工程理解。

## 高频问答

**Q：请一句话解释 {topic}。**
A：{essence}

**Q：{topic} 在什么场景用，什么场景不用？**
A：当业务真的存在对应瓶颈（如高并发、强一致、大数据量）时再引入；否则优先简单方案。

**Q：{topic} 和其他相近方案怎么选？**
A：按一致性、延迟、复杂度、运维成本四个维度权衡，没有万能答案。

## 答题模板

回答 {topic} 时按「定义 -> 场景 -> 风险 -> 方案 -> 观测指标」的顺序组织，既能讲清原理，也体现落地能力。

## 小结

面试官考 {topic}，本质是考你能否在 {family} 的复杂约束下做出权衡。把权衡讲清楚，分就稳了。
"""
    elif variant == "对比辨析":
        body = f"""# {title}

## 背景

{topic} 常与 {family} 里的其它概念混淆。把它们并排看清，才能用对地方。

## 对比维度

| 维度 | {topic} | 相近方案 |
| --- | --- | --- |
| 解决的问题 | {essence} | 解决不同层面的问题 |
| 适用阶段 | 设计与编码期 | 运行与观测期 |
| 引入成本 | 需评估复杂度 | 可能更低或更高 |
| 典型误用 | 在不需要时强行引入 | 该用却没用 |

## 选型建议

- 如果只是小规模验证，优先简单实现，不要过早引入 {topic}。
- 当指标明确显示瓶颈，再用 {topic} 做针对性优化，并保留回滚路径。

## 小结

{family} 里没有绝对优劣，{topic} 的价值取决于它是否恰好命中你的真实约束。
"""
    elif variant == "排查手册":
        body = f"""# {title}

## 背景

线上出现与 {topic} 相关的异常时，按手册逐步定位，避免盲人摸象。

## 现象

- 表现：{family} 相关接口变慢、报错或数据不一致。
- 范围：先确认是单个用户、单篇文章，还是全量请求都异常。
- 可复现：能否稳定复现，决定了排查路径。

## 定位步骤

1. 看指标：{topic} 相关监控（延迟、错误率、命中率）是否异常。
2. 看日志：按 traceId 串起链路，定位最先出错的节点。
3. 看数据：数据库记录、缓存状态、对象存储内容是否一致。
4. 做对照：是否有近期变更（发版、配置、流量）与时间线吻合。

## 修复方案

- 优先选择影响面小、可回滚、可验证的修复。
- 修复后观察指标回落，并补一条监控防止复发。

## 小结

{topic} 的排查核心是「先度量、再归因、后修复」，这也是 {family} 稳定性建设的通用方法。
"""
    else:  # 最佳实践
        body = f"""# {title}

## 背景

把 {topic} 用对、用好，靠的是沉淀下来的工程原则，而不是一次性改动。

## 设计原则

- 简单优先：能用简单方案解决的，不引入 {topic} 的复杂度。
- 可观测：为 {topic} 补充关键指标，问题发生前就能预警。
- 可回滚：任何涉及 {topic} 的改动都要有清晰的回滚路径。

## 落地清单

- [ ] 明确 {topic} 要解决的真实瓶颈
- [ ] 选定方案并评估一致性与延迟代价
- [ ] 补监控与告警
- [ ] 灰度发布并观察
- [ ] 沉淀文档，避免重复踩坑

## 度量指标

- 延迟：{topic} 引入前后的 P99 变化
- 错误率：相关异常是否下降
- 资源：CPU/内存/连接数的实际占用

## 小结

{topic} 的最佳实践可以浓缩成一句话：在 {family} 里用数据驱动决策，让每一次优化都可度量、可回滚。
"""
    return title, description, tags_json, body


def combos():
    out = []
    for fi, (family, base_tags, topics, examples) in enumerate(FAMILIES):
        for ti, (name, essence) in enumerate(topics):
            example = examples[ti % len(examples)]
            for vi, variant in enumerate(VARIANTS):
                out.append((family, base_tags, name, essence, example, variant))
    return out


# --------------------------------------------------------------------------
# DB / MinIO
# --------------------------------------------------------------------------
def mysql_conn():
    return pymysql.connect(**MYSQL)


def s3_client():
    return boto3.client("s3", endpoint_url=MINIO["endpoint_url"],
                        aws_access_key_id=MINIO["aws_access_key_id"],
                        aws_secret_access_key=MINIO["aws_secret_access_key"],
                        region_name="us-east-1",
                        config=Config(s3={"addressing_style": "path"}))


def current_published(cur):
    cur.execute("SELECT COUNT(*) FROM know_posts WHERE status='published' AND visible='public'")
    return cur.fetchone()[0]


def existing_seed_count(cur):
    cur.execute("SELECT COUNT(*) FROM know_posts WHERE title LIKE %s", (TITLE_PREFIX + "%",))
    return cur.fetchone()[0]


def load_state():
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            return {"created": []}
    return {"created": []}


def save_state(state):
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")


def create_one(conn, s3, family, base_tags, topic, essence, example, variant, idx, creator):
    title, description, tags_json, body = build_variant_body(family, topic, essence, example, variant, base_tags)
    raw = body.encode("utf-8")
    sha256 = hashlib.sha256(raw).hexdigest()

    with conn.cursor() as cur:
        cur.execute("SELECT UUID_SHORT()")
        pid = cur.fetchone()[0]
        cur.execute(
            """INSERT INTO know_posts
               (id, creator_id, status, type, visible, is_top, create_time, update_time)
               VALUES (%s, %s, 'draft', 'image_text', 'public', 0, NOW(), NOW())""",
            (pid, creator),
        )
    conn.commit()

    object_key = f"posts/{pid}/content.md"
    put = s3.put_object(Bucket=BUCKET, Key=object_key, Body=raw, ContentType="text/markdown")
    etag = put.get("ETag", "").strip('"')
    content_url = f"{PUBLIC_DOMAIN}/{BUCKET}/{object_key}"

    with conn.cursor() as cur:
        cur.execute(
            """UPDATE know_posts
               SET content_object_key=%s, content_etag=%s, content_size=%s, content_sha256=%s,
                   content_url=%s, title=%s, description=%s, tags=%s, visible='public', is_top=0,
                   status='published', publish_time=NOW(), update_time=NOW()
               WHERE id=%s""",
            (object_key, etag, len(raw), sha256, content_url, title, description, tags_json, pid),
        )
    conn.commit()
    return pid, title


def main():
    all_combos = combos()
    if len(all_combos) < TARGET:
        print(f"[warn] 话题组合数 {len(all_combos)} < 目标 {TARGET}，将达到上限后停止", flush=True)

    state = load_state()
    created_ids = state.get("created", [])

    conn = mysql_conn()
    s3 = s3_client()
    try:
        with conn.cursor() as cur:
            pub = current_published(cur)
            seed_n = existing_seed_count(cur)
        need = max(0, TARGET - pub)
        print(f"当前已发布+公开: {pub}  目标: {TARGET}  需新增: {need}", flush=True)
        if need == 0:
            print("已达到目标，无需新增。")
            return

        done = 0
        for i in range(need):
            combo = all_combos[(seed_n + i) % len(all_combos)]
            family, base_tags, topic, essence, example, variant = combo
            creator = CREATORS[(seed_n + i) % len(CREATORS)]
            try:
                pid, title = create_one(conn, s3, family, base_tags, topic, essence, example, variant, seed_n + i, creator)
                created_ids.append(pid)
                done += 1
                if (i + 1) % 25 == 0 or (i + 1) == need:
                    save_state({"created": created_ids})
                    print(f"[{i + 1}/{need}] id={pid} {title}", flush=True)
            except Exception as exc:
                print(f"[{i + 1}/{need}] FAILED {family}/{topic}/{variant}: {exc}", flush=True)
                conn.rollback()
                break
    finally:
        conn.close()

    save_state({"created": created_ids})
    with mysql_conn() as c2, c2.cursor() as cur:
        final = current_published(cur)
    print(json.dumps({"新增": done, "最终已发布": final, "目标": TARGET}, ensure_ascii=False))


if __name__ == "__main__":
    main()
