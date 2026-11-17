# Stripe Checkout 最小订单闭环

Spring Boot 3.5.16 + Java 21 + Stripe Java SDK + 文件型 H2，一次性付款示例。

完整进度、未完成项及新沙箱后台准备项见 [PAYMENT_CHECKLIST.md](PAYMENT_CHECKLIST.md)。

本项目用于学习和验证 Stripe 支付；商品、登录、订单页面仅辅助联调。用户已确认沙箱付款、Webhook 重发和本地订单状态更新跑通。正式项目将结合已有业务流程重新实现，不将本示例视为完整生产支付系统。

## 流程

创建本地订单 → 创建 Checkout Session → 跳转 Stripe → Webhook 验签并读取通知中的 Session → 核对金额与币种 → 更新订单 → 结果页查询本地订单。

商品为 Stubborn Attachments，1 件，USD 20.00。价格从后端订单快照构建 `price_data`，不接受前端金额，不需要预建 Product 或 Price。Session 创建与 PaymentIntent 生成只会在运行付款流程时发生；本轮开发不调用真实 Stripe 创建接口。

## 配置与运行

在正确的新沙箱中取得 API 凭据，优先使用具备所需权限的受限密钥（Checkout Session 创建和查询所需的相关资源权限）。所有凭据通过环境变量设置；不要放进源码或提交到仓库。

PowerShell 示例，尖括号内容为占位符：

```powershell
$env:STRIPE_API_KEY = '<新沙箱的 API 凭据>'
$env:STRIPE_WEBHOOK_SECRET = '<whsec 签名密钥>'
$env:APP_BASE_URL = 'http://localhost:4242'
mvn package
java -jar target/sample.jar
```

从项目根目录启动，访问 [商品页](http://localhost:4242/checkout.html)。也可以运行 `mvn spring-boot:run`，或在 VS Code 直接启动 `Server.main()`。应用自动读取工作目录中的 `.env.local`（Java properties 格式，每行 `名称=值`），环境变量优先；该文件已被 Git 忽略。必须同时提供 API 凭据和 Webhook 密钥才会启动，避免只跳转付款而没有支付确认。

静态页面仍在 `public/` 编辑，Maven 将它们打包到 JAR 的 `static/` 中；修改页面后需要重新构建。默认端口在 `src/main/resources/application.properties` 中配置，现有环境变量名称保持不变。数据库相对路径及 `.env.local` 均相对于启动工作目录；从其他目录启动时应提供对应环境变量和绝对数据库地址。

页面采用「纸间 PAPER & CO.」示例书店风格，包含商品页、付款结果页和返回书店页，并适配手机宽度。封面为本地 HTML 和 CSS 绘制的示意设计，不依赖外部图片服务。结果页根据查询到的订单展示待确认、处理中、成功、失败或过期状态；页面返回本身不代表付款成功。

先打开 [登录与注册](http://localhost:4242/login.html)，使用邮箱和密码注册。购买前和查看订单前需要登录。导航中的「我的订单」打开 [订单列表](http://localhost:4242/orders.html)，按创建时间倒序展示当前账号的订单，每页 20 笔，支持刷新、翻页及查看单笔详情。此页面只查询订单，不会创建付款或调用 Stripe。更换浏览器、会话过期或服务重启后，重新登录同一账号即可继续查看。

账号保存在同一 H2 数据库的 `app_user` 表中，密码使用 BCrypt 哈希。Spring Security 负责登录、退出、会话固定攻击防护和浏览器请求的 CSRF 校验。订单 `user_id` 来自服务端认证后的固定用户 ID，前端不能指定归属。`auth.js` 统一处理登录跳转、获取 CSRF token 及账号切换时清理当前购买请求。Webhook 仍使用 Stripe 签名校验，不要求用户登录或 CSRF token。

启动时会自动创建账号表，并给订单表新增 `user_id` 和索引；为兼容旧表结构，旧 `owner` 列暂时保留，新订单同时写入固定用户 ID。已有匿名订单保持原样，`user_id` 为空，不自动分配给新注册用户。它们仍保留在数据库里，但不会出现在账号订单列表中。

`Server` 负责启动和 Bean 装配，`PaymentController` 接收请求，`PaymentService` 使用 Spring 注入的 `StripeClient` 创建 Checkout Session，Webhook 直接读取验签后的通知对象，`OrderRepository` 负责持久化。`ResponseHeadersFilter` 设置通用响应头，`RequestErrorHandler` 统一处理异常。

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
| `GET /api/orders?page=0` | 当前登录账号的订单列表，每页 20 笔，返回 `orders`、从 0 开始的 `page` 和 `hasMore` |
| `GET /api/orders/{id}` | 查询当前登录账号所属订单，不接受前端指定归属；其他用户订单返回 404 |
| `GET /api/csrf` | 获取浏览器 POST 请求使用的 CSRF token 及请求头名称 |
| `POST /api/register` | 表单传邮箱 `email`、密码 `password`，创建本地账号 |
| `POST /api/login` | 表单传邮箱 `email`、密码 `password`，建立登录会话 |
| `GET /api/me` | 查询当前账号 ID 和邮箱，未登录返回 401 |
| `POST /api/logout` | 退出登录并清理服务端会话 |
| `POST /webhook` | 使用原始请求体验证 `Stripe-Signature`，读取通知中的 Session 并校验后更新订单，不额外查单 |

Webhook 使用 Snapshot（快照）事件，事件目标的 API 版本需与 Java SDK 兼容。无法安全反序列化时返回失败，不更新订单；修正版本配置后再重试投递。

订单状态为 `PENDING_PAYMENT`、`PAID`、`EXPIRED`；支付状态为 `UNPAID`、`PROCESSING`、`FAILED`、`PAID`。付款失败保留待支付订单，已付款不会被迟到的失败或过期通知降级。返回取消页不会取消订单。

Session 使用固定订单号作为 `client_reference_id` 和 metadata，PaymentIntent metadata 也附带订单号。固定 `integration_identifier` 只标识流程。

一次购买使用固定 Stripe 幂等键。重复通知的事件记录和订单变更在同一数据库事务提交。Session 配置一小时过期，以给网络重试预留时间；首次创建距离过期不足 30 分钟时停止创建，保留原订单等待核查。同一请求不要更改商品、域名等创建参数后重试。

## 存储和当前限制

默认数据库为 `data/orders.mv.db`，连接地址为 `jdbc:h2:./data/orders;AUTO_SERVER=TRUE`，可通过 `ORDER_DB_URL` 指定 JDBC 地址。自动混合模式支持本地应用与数据库工具共享文件库；工具也应使用包含 `AUTO_SERVER=TRUE` 的同一绝对路径连接，不能同时用普通嵌入模式独占文件。若自行配置了 `ORDER_DB_URL`，也需添加该参数。遇到文件被锁定后应修正连接方式并重启应用，不能删除锁文件强行打开。这仍是本地示例存储，不能作为生产部署方案。账号、订单和通知处理记录持久化；登录会话保存在进程内，重启或会话过期后需重新登录，但账号与新订单之间的关联不会丢失。当前本地账号不包含邮箱验证、密码找回或登录限流，这些仍需在正式上线前完善。

结果页轮询最多约一分钟，之后可手动刷新。它只读本地订单，未收到有效 Webhook 前不会显示付款成功。查询不触发主动查单补偿；网络故障产生的未绑定 Session 订单需要后续补偿机制。

防重复仅覆盖同一浏览器购买请求和 Stripe 幂等键，不能防止用户在多标签页或新会话发起多次独立购买。失败后对同一订单创建新支付尝试、发货与开通权益、退款、对账、生产级安全与监控尚未实现。

当前已移除 Gateway 抽象和自动化测试代码；此前的模拟测试记录见进度文件。本次调整只做编译和打包检查，真实新沙箱的配置和付款流程需另行联调。此前源码中出现过测试密钥，应在对应账户轮换；删除源码字符串不能撤销旧密钥。
