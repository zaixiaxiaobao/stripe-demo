# 付款流程进度

本文件是本项目的进度记录。代码实现、本地验证、Stripe 沙箱联调分别记录，不能互相替代。

## 项目目标

本项目用于学习并验证 Stripe 一次性 Checkout 支付接入。用户、商品、订单页面及本地数据库仅辅助联调，不继续按完整商城建设；正式项目将结合已有用户、订单及业务流程重新实现。后续工作围绕 Stripe 支付能力展开。

## 当前边界

- 只操作本地代码，不在 Stripe 创建、修改或删除资源，不发起测试付款。
- 用户已在沙箱完成付款，并确认 Webhook 重发后订单状态更新。
- 本轮已实现并完成主要联调：本地订单 → Checkout Session 关联 → Webhook 确认 → 订单结果查询。异步支付方式和生产环境尚未验证。

## 功能清单

| 功能 | 代码状态 | 验证状态与后续工作 |
| --- | --- | --- |
| 托管 Checkout，一次性付款 | 已有 | 用户已完成沙箱付款及订单状态更新联调 |
| `price_data` 动态商品价格 | 已有 | 固定示例商品，USD 20.00，无须预建 Product 或 Price |
| 固定流程统计标识 | 已有 | `payment_demo_etkplgpk`，无须后台申请 |
| 本地订单和价格快照 | 已实现 | 文件型 H2；本地测试确认重新打开数据库后记录仍在 |
| Session 与订单关联 | 已实现 | `client_reference_id`、Session 和 PaymentIntent metadata，保存 Session ID 与 PaymentIntent ID；伪 Gateway 验证 |
| Webhook 验签及金额、币种核对 | 已实现 | 当前直接读取验签后的快照 Session，不额外查单；用户重发真实沙箱通知，返回 200，数据库对应订单为 PAID |
| 支付成功、处理中、异步失败、Session 过期 | 已实现 | 本地模拟验证；已付款不可被迟到通知降级 |
| 重复创建请求和重复通知处理 | 基础已实现 | 同次购买固定请求标识和 Stripe 幂等键，通知记录和订单同事务；重复及并发通知测试通过；跨标签页防重复购买仍待办 |
| 结果查询与页面状态展示 | 已实现 | 当前登录账号归属校验，结果页只读订单、有限轮询；本轮未做运行时联调 |
| 我的订单列表 | 已实现 | `orders.html`；按固定用户 ID 隔离，创建时间倒序、每页 20 笔、刷新、详情入口及空状态；未做运行时联调 |
| 取消页回跳 | 已实现 | 配置取消 URL；返回取消页不等于订单取消；真实回跳待沙箱验证 |
| 密钥外部配置 | 已实现 | API 与 Webhook 密钥通过环境变量注入；原测试密钥轮换未执行，需在其所属账户处理 |
| Spark 迁移 Spring Boot | 已实现 | Spring MVC 路由、Servlet 会话、原始 Webhook 请求体验签；静态页面打入可执行 JAR |
| 正式商品目录、数量与服务端计价 | 待办 | 当前只支持固定示例商品 |
| 登录用户归属和访问控制 | 本地基础已实现 | 邮箱密码注册登录、BCrypt、Spring Security、CSRF、退出登录；账号持久化，重新登录同一账号查看订单；邮箱验证、密码找回、登录限流待办 |
| 支付失败后的再次付款 | 待办 | 需要设计同一订单的多次支付尝试及防重复付款 |
| 订单主动取消、超时关单 | 待办 | 需要处理正在付款与关单之间的竞争 |
| 发货、权益开通、通知及履约重试 | 待办 | 本轮只确认支付，不模拟业务履约完成 |
| 退款及退款状态同步 | 待办 | 包含部分退款、重复请求和异步结果 |
| 主动查单补偿、对账 | 待办 | Webhook 丢失或处理失败时补偿 |
| 日志、告警、运行监控 | 待办 | 本轮仅基本错误处理 |
| 生产部署及安全检查 | 待办 | HTTPS、凭据管理、备份、权限等 |

## 新沙箱：哪些需要后台准备

| 项目 | 是否必须预建 | 说明 |
| --- | --- | --- |
| API 凭据 | 必须配置 | 从正确沙箱获取凭据，优先按最小权限使用受限密钥；本地通过环境变量注入 |
| Webhook 事件目标 | 部署后必须配置 | 配置应用的 HTTPS 通知地址和事件，取得该目标自己的 `whsec_...` |
| 本地 Webhook 转发 | 本地联调需要 | 可用 Stripe CLI `listen`，使用 CLI 输出的签名密钥；它与后台事件目标密钥不同 |
| Product、Price | 不需要 | 使用内联 `price_data` 和 `product_data`；运行付款时 Stripe 会创建相关资源，并非完全不创建资源 |
| Customer | 当前不需要预建 | 当前示例不要求预先创建 Customer |
| Checkout Session | 不需要预建 | 应由后端在用户付款时创建，本轮不调用创建 API |
| PaymentIntent | 不需要预建 | Checkout 管理付款流程，无须额外手动创建 |
| 流程统计标识 | 不需要 | 由代码提供固定名称 |
| 支付方式设置 | 需要确认 | 确认目标沙箱支持并启用所需方式，代码不写死方式列表 |
| 税务、优惠券、订阅价格、Connect 账户 | 当前不需要 | 本轮不包含这些业务 |

正式部署订阅事件：`checkout.session.completed`、`checkout.session.async_payment_succeeded`、`checkout.session.async_payment_failed`、`checkout.session.expired`。

## 验证记录

- 2026-11-17：用户确认沙箱付款、Webhook 手动重发及本地订单状态更新已跑通。只读数据库核对确认重发事件对应订单为 `PAID`。当前一次性 Checkout 核心流程作为学习示例已完成主要联调；主动查单补偿、退款、对账及正式项目接入另行开展，不视为已完成生产支付系统。
- 2026-11-17：注册和登录报错的本地 H2 日志显示文件锁定，与用户保持数据库工具连接的情况一致。通过现有 DBX 连接查询 H2 版本，并用应用现有 JDBC 驱动加 `AUTO_SERVER=TRUE;IFEXISTS=TRUE` 完成只读查询，证明共享连接可用。默认 JDBC 地址启用自动混合模式；数据库异常返回 503 和对应提示，登录服务异常不再伪装成密码错误。未创建账号、修改业务数据或调用 Stripe，应用需重启后使用新配置。
- 2026-11-16：按用户选择增加本地邮箱密码注册登录，新增 `app_user` 及订单 `user_id`，订单只允许创建者账号查询；已存在的匿名订单不自动认领。已完成脚本语法检查及跳过测试的编译和打包；按要求未运行测试、未启动真实数据迁移、未调用 Stripe，登录和跨账号隔离仍待运行时联调。
- 2026-11-16：新增「我的订单」列表页及 `GET /api/orders?page=0` 接口。归属由服务端当前会话确定，接口按归属筛选、分页查询，只返回商品、金额、状态及创建时间等展示字段。脚本语法检查、编译和打包通过，未运行测试、未调用 Stripe，运行时联调未完成。
- 2026-11-16：重做商品、付款结果及返回书店页面，统一暖白与深绿书店风格，使用本地 CSS 示意封面，增加手机布局、订单明细和支付状态展示。JavaScript 语法检查及跳过测试的打包通过；未调用 Stripe、未运行付款测试。本机 Edge 无头预览渲染进程报错，未完成浏览器截图验收。
- 2026-11-16：Webhook 改为直接读取验签后的 Session 快照，删除再次查询 Stripe 的调用。保留金额、币种、订单关联校验及通知去重、已付款不可降级逻辑；API 版本不兼容时返回失败。编译和打包通过，本次按要求不运行测试，也未做真实沙箱联调。
- 2026-11-16：按用户要求删除 `PaymentGateway`、`StripeGateway` 及相关自动化测试和测试依赖，`PaymentService` 直接注入 `StripeClient`。跳过测试的干净编译和打包通过；下方测试通过记录属于删除前的历史版本。
- 2026-11-16：迁移至 Spring Boot 3.5.16、Java 21；干净构建及可执行 `target/sample.jar` 打包成功，19 个测试全部通过（原有 14 个支付测试、新增 5 个 Spring HTTP 测试）。新增覆盖静态资源、表单提交、重复请求、会话归属、跨源拒绝、中文 Webhook 原始请求体验签、篡改与空请求拒绝、404 和 405 状态码。
- 新 JAR 从其他工作目录启动，10 项实际 HTTP 冒烟通过，包括页面、脚本、未知订单、无效创建、跨源拒绝、缺签名、中文有效签名和篡改拒绝；使用临时端口、内存 H2 和本地假凭据，未调用 Stripe。脚本为本机生成的 `target/smoke-boot.ps1`。
- VS Code 启动配置中的本地凭据移到被忽略的 `.env.local`，启动入口仍为 `Server.main()`。未轮换账户密钥，也未调用真实 Stripe API。
- 2026-11-13：编译及打包成功，生成可运行的依赖 JAR。
- 14 个 JUnit 测试通过：服务端价格、订单关联、重复请求、响应丢失重试的稳定幂等键、会话隔离、付款成功、异步处理和失败、过期、并发重复通知、迟到通知、签名篡改、金额和币种及关联错误、通知早于创建响应、数据库重新打开。
- `node --check` 验证 `checkout.js`、`order.js` 通过。
- 打包 JAR 的 10 项本地 HTTP 冒烟通过：商品页和脚本、成功页、取消页、未知订单、无效创建请求、跨源请求拒绝、无签名通知、含中文的有效签名通知（不属于付款事件，不调用 Stripe）、篡改通知拒绝。
- 本机 Maven 全局仓库配置与缓存来源不一致；通过 `target/offline-settings.xml` 和显式本地仓库完成离线验证，未更改全局 Maven 配置。验证命令：

  ```powershell
  mvn -o -gs target/offline-settings.xml -s target/offline-settings.xml '-Dmaven.repo.local=C:/Users/junba/.m2/repository' test
  mvn -o -gs target/offline-settings.xml -s target/offline-settings.xml '-Dmaven.repo.local=C:/Users/junba/.m2/repository' -DskipTests package
  powershell -NoProfile -ExecutionPolicy Bypass -File target/smoke.ps1
  ```

- `target/` 下的验证辅助文件仅为本机生成物，不纳入版本管理。后续环境仓库正常时使用 README 的标准命令。
- 未调用 Stripe API，未创建、修改或删除 Stripe 资源，未发起付款。没有验证新沙箱凭据、受限密钥权限、支付方式、真实通知和用户付款回跳。
- 未实现发货或开通权益，因此“订单已付款”不代表业务已交付。

## 代码位置

- `Server.java`：Spring Boot 启动、外部配置、订单仓库与支付服务 Bean 装配。
- `PaymentController.java`：付款创建、订单列表与详情、Webhook；订单归属取自认证用户。
- `SecurityConfig.java`、`AccountController.java`、`UserRepository.java`：Spring Security、注册登录与当前账号接口、本地账号持久化。
- `public/login.html`、`public/login.js`、`public/auth.js`：登录注册页面、导航账号状态、CSRF 请求和登录跳转。
- `src/main/resources/application.properties`：端口、环境变量映射和本地配置文件导入。
- `PaymentService.java`：创建 Checkout、关联订单、验签后读取通知中的 Session、核对付款。
- `OrderRepository.java`：H2 表、订单快照、Session 绑定、通知去重和事务更新。
- `public/checkout.js`、`public/order.js`：创建请求、跳转和订单状态展示。

## 官方文档

- [Checkout 订单履约与 Webhook](https://docs.stripe.com/checkout/fulfillment)
- [创建 Checkout Session](https://docs.stripe.com/api/checkout/sessions/create)
- [Webhook 签名验证与重试](https://docs.stripe.com/webhooks)
- [自定义结果页](https://docs.stripe.com/payments/checkout/custom-success-page)
