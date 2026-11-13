# Stripe Checkout 最小订单闭环

Spark + Stripe Java SDK + 文件型 H2，一次性付款示例。

完整进度、未完成项及新沙箱后台准备项见 [PAYMENT_CHECKLIST.md](PAYMENT_CHECKLIST.md)。

## 流程

创建本地订单 → 创建 Checkout Session → 跳转 Stripe → Webhook 验签并查回 Session → 核对金额与币种 → 更新订单 → 结果页查询本地订单。

商品为 Stubborn Attachments，1 件，USD 20.00。价格从后端订单快照构建 `price_data`，不接受前端金额，不需要预建 Product 或 Price。Session 创建与 PaymentIntent 生成只会在运行付款流程时发生；本轮开发不调用真实 Stripe 创建接口。

## 配置与运行

在正确的新沙箱中取得 API 凭据，优先使用具备所需权限的受限密钥（Checkout Session 创建和查询所需的相关资源权限）。所有凭据通过环境变量设置；不要放进源码或提交到仓库。

PowerShell 示例，尖括号内容为占位符：

```powershell
$env:STRIPE_API_KEY = '<新沙箱的 API 凭据>'
$env:STRIPE_WEBHOOK_SECRET = '<whsec 签名密钥>'
$env:APP_BASE_URL = 'http://localhost:4242'
mvn test package
java -jar target/sample-jar-with-dependencies.jar
```

从项目根目录启动，访问 [商品页](http://localhost:4242/checkout.html)。`.env.example` 仅说明配置，应用不会自动读取 `.env` 文件。必须同时提供 API 凭据和 Webhook 密钥才会启动，避免只跳转付款而没有支付确认。

本地联调时，先确认 Stripe CLI 连接的是目标沙箱，再由你执行：

```powershell
stripe listen --forward-to localhost:4242/webhook
```

使用 CLI 输出的 `whsec_...` 设置 `STRIPE_WEBHOOK_SECRET` 后启动应用。CLI 转发密钥和后台配置的事件目标密钥不同。部署后需在目标沙箱配置 HTTPS Webhook 事件目标，监听：

- `checkout.session.completed`
- `checkout.session.async_payment_succeeded`
- `checkout.session.async_payment_failed`
- `checkout.session.expired`

以上命令用于后续联调说明，本轮不执行 Stripe CLI 登录、转发、触发事件或付款。

## 接口与状态

| 接口 | 用途 |
| --- | --- |
| `POST /create-checkout-session` | 表单传 `requestKey`（UUID），返回订单号和跳转 URL；浏览器同次购买重试沿用该值 |
| `GET /api/orders/:id` | 查询当前匿名浏览器会话所属订单，不接受前端指定归属 |
| `POST /webhook` | 使用原始请求体验证 `Stripe-Signature`，查回 Stripe Session 后更新订单 |

订单状态为 `PENDING_PAYMENT`、`PAID`、`EXPIRED`；支付状态为 `UNPAID`、`PROCESSING`、`FAILED`、`PAID`。付款失败保留待支付订单，已付款不会被迟到的失败或过期通知降级。返回取消页不会取消订单。

Session 使用固定订单号作为 `client_reference_id` 和 metadata，PaymentIntent metadata 也附带订单号。固定 `integration_identifier` 只标识流程。

一次购买使用固定 Stripe 幂等键。重复通知的事件记录和订单变更在同一数据库事务提交。Session 配置一小时过期，以给网络重试预留时间；首次创建距离过期不足 30 分钟时停止创建，保留原订单等待核查。同一请求不要更改商品、域名等创建参数后重试。

## 存储和当前限制

默认数据库为 `data/orders.mv.db`，可通过 `ORDER_DB_URL` 指定 JDBC 地址。仅供本地单进程示例，不能作为生产部署方案。订单和通知处理记录持久化；匿名浏览器会话保存在进程内，重启或会话过期后不能继续查询原订单，正式用户归属仍在待办中。

结果页轮询最多约一分钟，之后可手动刷新。它只读本地订单，未收到有效 Webhook 前不会显示付款成功。查询不触发主动查单补偿；网络故障产生的未绑定 Session 订单需要后续补偿机制。

防重复仅覆盖同一浏览器购买请求和 Stripe 幂等键，不能防止用户在多标签页或新会话发起多次独立购买。失败后对同一订单创建新支付尝试、发货与开通权益、退款、对账、生产级安全与监控尚未实现。

测试使用伪 Stripe Gateway、本地 HMAC 签名和本地 H2，不创建或修改 Stripe 资源。真实新沙箱的配置和付款流程需另行联调。此前源码中出现过测试密钥，应在对应账户轮换；删除源码字符串不能撤销旧密钥。
