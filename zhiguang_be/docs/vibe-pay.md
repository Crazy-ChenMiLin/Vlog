# Vibe Pay 买断接入

## 本地运行

后端 `.env` 已准备好支付宝参数；该文件不会被 Spring Boot 自动加载。打开 PowerShell，执行：

```powershell
cd D:\resume-project\zhiguang_be
Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') {
        $name = $matches[1]
        $value = $matches[2].Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
}
mvn spring-boot:run
```

再打开一个 PowerShell 窗口启动前端：

```powershell
cd D:\resume-project\zhiguang_fe\zhiguang_fe-main
npm run dev
```

访问 Vite 输出的地址并通过验证码登录，点顶部 VIP。购买返回 URL 可不配置；手机从支付宝返回后手动打开 VIP 页并刷新。localhost 无法作为 HTTPS 购买回跳地址。需要 MySQL、Redis、Elasticsearch、Nacos 等已有依赖可访问；不要在生产环境反复运行有副作用的测试。

## 云端配置

代码推送到 GitHub 不会上传本地 .env。首次部署前，需要在 GitHub 仓库 Settings → Secrets and variables → Actions → Repository secrets 中设置：

- `ALIPAY_APP_ID`：支付宝应用 ID
- `ALIPAY_PRIVATE_KEY`：应用私钥，取本机后端 .env 中同名值
- `ALIPAY_PUBLIC_KEY`：支付宝公钥，取本机后端 .env 中同名值

可选设置 Actions variable `VIBE_PAY_CALLBACK_URL` 为前端 HTTPS 地址，例如 `https://web.chenmilin.xyz/vip`。工作流默认使用该地址。付款后的跳转仅是页面返回，必须重新查询权益。

工作流会将有值的 Secrets 写入后端服务器的受控 runtime .env 并传给容器。也可以由服务器管理员直接在 `/home/chenmilin/zhiguang-deploy/runtime/.env` 设置上述变量；Secret 为空时不会覆盖服务器已有配置。绝不要将密钥写进 Git 仓库。

## 联调

1. 确认商品属于当前应用商户、模式为 ONE_TIME 且状态可售。
2. 登录知光，在顶部进入 VIP。电脑使用支付宝扫码入口，手机可尝试打开支付宝购买。
3. 完成支付后回到 VIP 页刷新，只有支付宝返回 ALLOW 才显示已开通。返回页面或查询失败不代表支付成功。
4. 商品收费状态通过 `GET /api/v1/payment/product` 查询；当前登录用户权益通过 `GET /api/v1/payment/consult` 查询，用户身份来自认证令牌。

当前只实现购买及权益查询，尚未对全站接口增加 VIP 限制。
