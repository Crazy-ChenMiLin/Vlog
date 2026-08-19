# 知光后端 ARMS 应用监控「无数据」排查纪实

> - 服务器：`100.83.242.114`（内网，非阿里云 ECS，tailscale 内网 hostname `chenmilin`）
> - 应用：`zhiguang-be`（知光后端，Docker 容器，`network_mode: host`）
> - 探针：`aliyun-java-agent`（基于 OpenTelemetry 的 ARMS 3.x Java 探针）
> - LicenseKey：`c2rshm8dsd@021b43083e64db1`　地域：华东1（杭州）`cn-hangzhou`
> - 探针日志：`/opt/arms/AliyunJavaAgent/logs/boot.log`（容器内）
> - 排查日期：2026-08-15

---

## 一、需求与目标

知光后端部署在一台**阿里云之外的内网服务器**上，需要接入阿里云 **ARMS 应用监控**（调用链 / JVM 指标 / 业务日志），以便在 ARMS 控制台观测应用健康度。

**预期结果**：ARMS 控制台「实例数 = 1」，能看到调用链、JVM/应用指标、日志。

---

## 二、问题现象

- ARMS 控制台实例数长期为 **0**，无任何监控数据。
- 探针随容器启动，但所有上报失败。
- 探针日志 `boot.log` 持续报错（详见下文）。

---

## 三、排查时间线与关键发现

### 1. 只读诊断：定位「连不上」

`boot.log` 关键报错：
- `[ArmsOtlpHttpExporter] Connect timed out`
- `[NetworkUtils] Try to connect address http://arms-dc-hz-internal.aliyuncs.com ... fail! Connect timed out`

用 `getent hosts <域名>` 验证：这台服务器的内网 DNS 把 **`*.aliyuncs.com` 全部劫持到内网 VIP 段**（`100.103.x`、`10.x`）。
- 验证非阿里云 ECS：`100.100.100.200`（阿里云 metadata 服务）无响应。
- 验证公网可达：`arms-dc-hz.aliyuncs.com` 公网 443 **可连**。

**结论**：内网 DNS 劫持 → agent 硬编码连接的内网接入点域名解析到不可达的内网 IP。

> **为什么 1Panel 能拉容器、ARMS 却不行？**
> 劫持只针对 `*.aliyuncs.com` 这类阿里云域名；`docker.io` 等公网域名走公共 DNS 正常解析。1Panel 拉镜像不依赖 aliyuncs 域名，故不受影响；ARMS 探针强依赖 aliyuncs 接入点域名，故中招。两者不矛盾。

### 2. 方案 A（`arms-agent.properties` 强制公网 endpoint）— 失败

在 `arms-agent.properties` 加了 4 行：
```
profiler.collector.trace.endpoint=arms-dc-hz.aliyuncs.com
profiler.collector.metric.endpoint=arms-dc-hz.aliyuncs.com
profiler.collector.meta.endpoint=arms-dc-hz.aliyuncs.com
```
重启后仍 `Connect timed out`，且仍连 `arms-dc-hz-internal` / `*-intranet` 内网域名 → **配置项未被该 agent 版本识别（agent 硬编码接入点域名）**。已还原为原始 2 行。

### 3. 方案 B（改 `resolv.conf` 换公共 DNS）— 验证无效

用公共 DNS `223.5.5.5` 解析内网版域名 `arms-dc-hz-internal.aliyuncs.com`，仍返回 `100.103.107.100/101`（内网 VIP）→ **内网版域名天生指向内网，换 DNS 救不了**（公共 DNS 上它本就指向内网 VIP）。

### 4. 方案 C（`/etc/hosts` 强制公网 IP）— 网络打通，暴露 404

把内网域名写死到公网 IP（初版**全部指 `121.43.177.209`**）。重启后：
- `Connect timed out` 消失 → **网络层打通**。
- 出现 `404 Not Found` → 请求已到达服务器，但路径不对（次级问题暴露）。
- 补了日志域名 `end-side-logs-cn-hangzhou.cn-hangzhou-intranet.log.aliyuncs.com` 的映射。

### 5. 404 根因深挖：发现 IP 指错（关键转折）

查阿里云官方文档《Java 应用监控网络配置》，杭州公网接入点域名体系如下（内网版域名均被本机 DNS 劫持）：

| 用途 | 公网版域名 | 真实公网 IP | 内网版域名（被劫持） |
|---|---|---|---|
| 调用链&元数据 | `arms-dc-hz.aliyuncs.com` | **121.43.177.209** | `arms-dc-hz-internal.aliyuncs.com` |
| 指标 | `cn-hangzhou.arms.aliyuncs.com` | **47.98.97.63** | `cn-hangzhou-intranet.arms.aliyuncs.com` |
| 日志 | `cn-hangzhou.log.aliyuncs.com` | 47.97.247.71 | `cn-hangzhou-intranet.log.aliyuncs.com` |
| 探针自监控日志 | `end-side-logs-cn-hangzhou.cn-hangzhou.log.aliyuncs.com` | 47.118.98.45 | `end-side-logs-cn-hangzhou.cn-hangzhou-intranet.log.aliyuncs.com` |
| ACM 配置 | `acm.aliyun.com` | 106.15.100.99 | `addr-hz-internal.edas.aliyun.com` |
| 持续剖析 OSS | `oss-cn-hangzhou.aliyuncs.com` | 118.31.219.227 | `oss-cn-hangzhou-internal.aliyuncs.com` |

`boot.log` 去重后，agent 实际连接的内网域名共 7 个（上面后 6 行 + `arms-dc-hz-intranet` 变体）。

**带真实 OTLP protobuf body 实测（curl `--resolve` 绕过 hosts 独立验证）：**

| 目标 IP | 路径 | 结果 |
|---|---|---|
| `47.98.97.63`（指标网关） | `/collector/arms/ot/{license}/{pid}` | **200** ✅ |
| `121.43.177.209`（arms-dc-hz） | `/collector/arms/ot/{license}/{pid}` | **404** ❌ |
| `121.43.177.209`（arms-dc-hz） | `/api/v1/arms/otel/{license}/{pid}` | **200** ✅ |
| `47.98.97.63` | `/api/v1/arms/otel/{license}/{pid}` | **404** ❌ |

**真相**：
- **指标（metric）**走 `cn-hangzhou-intranet.arms` → 应映射 `47.98.97.63`，路径 `/collector/arms/ot/...`
- **调用链（trace/span）**走 `arms-dc-hz-internal` → 应映射 `121.43.177.209`（arms-dc-hz），路径 `/api/v1/arms/otel/...`
- 方案 C 初版把所有 internal 域名**无脑指到 `121.43.177.209`** 是错的——指标接入点和调用链接入点是**不同 IP、不同路径**，不能混用。

### 6. 当前线上 hosts（精确版，但调用链两行待修正）

```
47.98.97.63   arms-dc-hz-internal.aliyuncs.com        ← 错，应 121.43.177.209
47.98.97.63   arms-dc-hz-intranet.aliyuncs.com        ← 错，应 121.43.177.209
47.98.97.63   cn-hangzhou-intranet.arms.aliyuncs.com  ← 对
47.97.247.71  cn-hangzhou-intranet.log.aliyuncs.com    ← 对（日志 SLS）
47.118.98.45  end-side-logs-cn-hangzhou.cn-hangzhou-intranet.log.aliyuncs.com ← 对
106.15.100.99 addr-hz-internal.edas.aliyun.com         ← ACM，对
118.31.219.227 oss-cn-hangzhou-internal.aliyuncs.com   ← OSS，对
```

由于 `arms-dc-hz-internal/intranet` 错指 `47.98.97.63`，重启后日志出现：
- metric 的 404 消失 → **指标已通** ✅
- 新 404：`Send span error /api/v1/arms/otel/...`（调用链打到错误 IP）
- `arms-dc-hz-internal:9990/health/readiness Connect timed out`（9990 在 `47.98.97.63` 不通）

---

## 四、根因总结

1. **主因（环境）**：内网 DNS 把 `*.aliyuncs.com` 劫持到内网 VIP，agent 硬编码内网接入点域名 → 连不上（Connect timed out）。
2. **次因（修复引入）**：指标接入点 `cn-hangzhou-intranet.arms` 与调用链接入点 `arms-dc-hz-internal` 公网 IP 不同（47.98.97.63 vs 121.43.177.209），方案 C 初版混指到同一 IP，导致调用链路径 404。
3. **方案 A/B 无效原因**：agent 硬编码接入点域名，配置项不生效；内网版域名天生内网，换 DNS 无效。

---

## 五、最终正确映射（收尾修正）

把 `arms-dc-hz-internal` / `arms-dc-hz-intranet` 改回 `121.43.177.209`（调用链&元数据、9990），其余保持不变：

```
121.43.177.209 arms-dc-hz-internal.aliyuncs.com
121.43.177.209 arms-dc-hz-intranet.aliyuncs.com
47.98.97.63    cn-hangzhou-intranet.arms.aliyuncs.com
47.97.247.71   cn-hangzhou-intranet.log.aliyuncs.com
47.118.98.45   end-side-logs-cn-hangzhou.cn-hangzhou-intranet.log.aliyuncs.com
106.15.100.99  addr-hz-internal.edas.aliyun.com
118.31.219.227 oss-cn-hangzhou-internal.aliyuncs.com
```

改完执行 `cd /home/chenmilin/zhiguang-deploy/runtime && docker compose restart zhiguang-be`，等 1~2 分钟，调用链 404 应消失。

---

## 六、回滚与备份

- 备份：`/etc/hosts.bak.arms.<时间戳>`（多个）
- 回滚：`cp /etc/hosts.bak.arms.<最新> /etc/hosts && docker compose restart zhiguang-be`

---

## 七、残留非阻断问题（验证后可观察）

- **9990 健康检查**：`arms-dc-hz-internal:9990/health/readiness` 在 `121.43.177.209` 上实测 `000`（端口未对 curl 的 https 开放或需 http）。属元数据/健康探测，**非核心数据上报**，不影响指标/调用链主链路。
- **STS 凭证超时**：`GetSTSCredential Connect timed out`。agent 探测可选 STS 能力失败，**不影响 LicenseKey 直接鉴权的主上报**。
- **日志 SLS 404**：`LogException{httpCode=404}`。SLS 上报域名已指对公网 IP，仍 404 可能是 project/logstore 鉴权或路径问题，需到 ARMS/SLS 控制台确认 LicenseKey 对应的日志 project 是否存在。

---

## 八、验收步骤

1. 按第五节修正 hosts 映射并重启容器。
2. 看 `boot.log`：无 `Connect timed out`、无 `Send span/metric error 404`。
3. 到 ARMS 控制台：实例数 1，调用链 + JVM 指标出现。
4. 日志 404 若仍在，单独排查 SLS project 鉴权。
