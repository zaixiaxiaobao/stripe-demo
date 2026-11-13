# 付款流程进度

本文件是本项目的进度记录。代码实现、本地验证、Stripe 沙箱联调分别记录，不能互相替代。

## 当前边界

- 只操作本地代码，不在 Stripe 创建、修改或删除资源，不发起测试付款。
- 用户已经新建沙箱。本项目尚未验证该沙箱的凭据、配置或支付结果。
- 本轮已实现：本地订单 → Checkout Session 关联 → Webhook 确认 → 订单结果查询。只完成本地验证，沙箱联调未完成。

## 功能清单

| 功能 | 代码状态 | 验证状态与后续工作 |
| --- | --- | --- |
| 托管 Checkout，一次性付款 | 已有 | 未做当前沙箱联调 |
| `price_data` 动态商品价格 | 已有 | 固定示例商品，USD 20.00，无须预建 Product 或 Price |
| 固定流程统计标识 | 已有 | `payment_demo_etkplgpk`，无须后台申请 |
| 本地订单和价格快照 | 已实现 | 文件型 H2；本地测试确认重新打开数据库后记录仍在 |
| Session 与订单关联 | 已实现 | `client_reference_id`、Session 和 PaymentIntent metadata，保存 Session ID 与 PaymentIntent ID；伪 Gateway 验证 |
| Webhook 验签及金额、币种核对 | 已实现 | 本地真实 HMAC 验签测试，使用伪 Gateway 查回 Session；尚未接收真实 Stripe 通知 |
| 支付成功、处理中、异步失败、Session 过期 | 已实现 | 本地模拟验证；已付款不可被迟到通知降级 |
| 重复创建请求和重复通知处理 | 基础已实现 | 同次购买固定请求标识和 Stripe 幂等键，通知记录和订单同事务；重复及并发通知测试通过；跨标签页防重复购买仍待办 |
| 结果查询与页面状态展示 | 已实现 | 浏览器会话归属校验，结果页只读订单、有限轮询；接口和静态页面冒烟通过，真实浏览器付款待验证 |
| 取消页回跳 | 已实现 | 配置取消 URL；返回取消页不等于订单取消；真实回跳待沙箱验证 |
| 密钥外部配置 | 已实现 | API 与 Webhook 密钥通过环境变量注入；原测试密钥轮换未执行，需在其所属账户处理 |
| 正式商品目录、数量与服务端计价 | 待办 | 当前只支持固定示例商品 |
| 登录用户归属和访问控制 | 待办 | 本轮仅匿名浏览器会话隔离 |
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

- `Server.java`：启动、外部配置、三个 HTTP 接口和匿名会话隔离。
- `PaymentService.java`：创建 Checkout、关联订单、验签后查回 Session、核对付款。
- `OrderRepository.java`：H2 表、订单快照、Session 绑定、通知去重和事务更新。
- `public/checkout.js`、`public/order.js`：创建请求、跳转和订单状态展示。
- `src/test/java/com/stripe/sample/PaymentServiceTest.java`：无真实 Stripe 调用的自动化测试。

## 官方文档

- [Checkout 订单履约与 Webhook](https://docs.stripe.com/checkout/fulfillment)
- [创建 Checkout Session](https://docs.stripe.com/api/checkout/sessions/create)
- [Webhook 签名验证与重试](https://docs.stripe.com/webhooks)
- [自定义结果页](https://docs.stripe.com/payments/checkout/custom-success-page)
