# 知光后端配置

本地和内网后端从同一个 Nacos 服务读取 YAML。配置入口是 Nacos 控制台的配置管理。

| 环境 | 命名空间 ID | Group | Data ID |
| --- | --- | --- | --- |
| 本地 Maven、IDEA 或 Docker | `zhiguang-dev` | `ZHIGUANG_GROUP` | `zhiguang-runtime.yaml` |
| 云端后端 | `zhiguang-prod` | `ZHIGUANG_GROUP` | `zhiguang-runtime.yaml` |

修改模型、限流、功能开关、数据服务地址和 OAuth 回调时，在相应命名空间发布。GitHub Actions 只读验证生产配置，随后部署代码。已经支持刷新的 RAG 参数可以热更新。数据库、AI 客户端、鉴权凭据和监听端口在启动时加载，改动后重启对应后端。

Nacos 已启用客户端鉴权。数据库密码、API Key、JWT 密钥位置、OAuth Secret 等使用受限的 `backend-secrets.yml`。它只存凭据，放在仓库外。本机默认使用 `~/.zhiguang/backend-secrets.yml`。云端 Compose 将 `runtime/backend-secrets.yml` 和 `runtime/java.options` 以只读方式挂载。Nacos 地址、连接凭据和命名空间可由进程启动环境提供。

## 本地启动

已有 `zhiguang-dev` 配置和本机密钥文件时，由本机启动环境注入 `NACOS_USERNAME` 和 `NACOS_PASSWORD`，再执行 `mvn spring-boot:run` 或使用 IDEA 启动。默认监听 `8080`。Docker 可使用相同配置。

```powershell
$env:COMPOSE_DISABLE_ENV_FILE = '1'
docker compose up -d --build zhiguang-be
```

Compose 的 `middleware` profile 用于单独创建开发用 MySQL、Redis、Kafka、Elasticsearch 和 MinIO。只有需要启动这些服务时，才应给 Compose 进程提供各自的初始化凭据，并在 `zhiguang-dev` 中指向对应实例。Docker 的容器内地址和 Windows 上 Maven 可访问的地址要与各自的运行方式匹配。

## 一次性迁移

迁移工具需要 Python 和 PyYAML。它只创建目标 Data ID，读取原有 `public` RAG 配置并补齐环境参数，真实凭据写入指定的仓库外目录。`NACOS_NAMESPACE` 必须指定为 `zhiguang-dev` 或 `zhiguang-prod`，工具在 Data ID 已经存在时退出。

本地可以从旧文件导入已有值。

```powershell
$env:NACOS_NAMESPACE = 'zhiguang-dev'
python scripts/deploy/nacos-runtime.py migrate --legacy-env .env --output (Join-Path $HOME '.zhiguang')
```

生产可以在内网服务器上从当前运行的容器导入实际生效的值。

```bash
NACOS_NAMESPACE=zhiguang-prod python3 scripts/deploy/nacos-runtime.py migrate \
  --container zhiguang-be --output /home/chenmilin/zhiguang-deploy/runtime
```

生产部署脚本要求上述两个挂载文件就绪，并在构建前验证目标 Nacos 配置。原运行目录可以保留用于回退。Benchmark 的运行端仍通过 GitHub Secrets 取 HTTP 请求凭据，后端使用挂载文件中与它一致的值。
