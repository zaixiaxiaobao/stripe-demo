package com.stripe.sample;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.stripe.StripeClient;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;

public final class PaymentService {
  private final OrderRepository orders;
  private final StripeClient client;
  private final String baseUrl;
  private final String webhookSecret;

  PaymentService(OrderRepository orders, StripeClient client, String baseUrl, String webhookSecret) {
    this.orders = orders;
    this.client = client;
    this.baseUrl = baseUrl;
    this.webhookSecret = webhookSecret;
  }

  public Order checkout(String userId, String requestKey) throws Exception {
    if (requestKey == null || !requestKey.matches("[0-9a-fA-F-]{36}")) {
      throw new IllegalArgumentException("A UUID requestKey is required");
    }
    UUID.fromString(requestKey);
    Order order = orders.createOrGet(userId, requestKey);
    if (!"PENDING_PAYMENT".equals(order.status))
      return order;
    if (order.sessionId != null)
      return order;
    // Stripe 要求首次创建时，距离 expires_at 指定的过期时间至少还有 30 分钟。
    if (Instant.now().getEpochSecond() >= order.expiresAt - 1830) {
      throw new IllegalStateException("Checkout creation window expired; start a new purchase");
    }
    SessionCreateParams params = SessionCreateParams.builder()
        .setMode(SessionCreateParams.Mode.PAYMENT)
        .setSuccessUrl(baseUrl + "/success.html?order_id=" + order.id
            + "&session_id={CHECKOUT_SESSION_ID}")
        .setCancelUrl(baseUrl + "/cancel.html?order_id=" + order.id)
        .setClientReferenceId(order.id)
        .putMetadata("orderNo", order.id)
        // 支付意图
        .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
            .putMetadata("orderNo", order.id).build())
        // 过期时间
        .setExpiresAt(order.expiresAt)
        .setIntegrationIdentifier("payment_demo_etkplgpk")
        .addLineItem(SessionCreateParams.LineItem.builder()
            .setQuantity(order.quantity)
            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                .setCurrency(order.currency)
                .setUnitAmount(order.unitAmount)
                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                    .setName(order.productName).build())
                .build())
            .build())
        .build();
    // 同一订单始终使用固定幂等键，未收到明确网络响应时重试也保持不变。
    Session session = client.v1().checkout().sessions().create(params,
        RequestOptions.builder().setIdempotencyKey("checkout-" + order.id).build());
    validate(order, session);
    if (session.getUrl() == null)
      throw new IllegalStateException("Missing Checkout URL");
    orders.bindSession(order.id, session.getId(), session.getUrl());
    return orders.find(order.id);
  }

  public Order getOrder(String userId, String id) throws Exception {
    Order order = orders.find(id);
    return order != null && userId != null && userId.equals(order.userId) ? order : null;
  }

  public List<Order> listOrders(String userId, int page) throws Exception {
    if (page < 0 || page > 100000)
      throw new IllegalArgumentException("Invalid page");
    // 多取一条用于判断是否还有下一页；归属来自已认证的用户 ID。
    return userId == null ? List.of() : orders.findByUserId(userId, 21, page * 20);
  }

  public void webhook(String rawBody, String signature) throws Exception {
    Event event = Webhook.constructEvent(rawBody, signature, webhookSecret);
    String type = event.getType();
    if (!"checkout.session.completed".equals(type)
        && !"checkout.session.async_payment_succeeded".equals(type)
        && !"checkout.session.async_payment_failed".equals(type)
        && !"checkout.session.expired".equals(type))
      return;
    // 直接使用验签后的快照事件对象，不再请求 Stripe 查询 Session。
    // Webhook API 版本需与 SDK 兼容；不兼容时拒绝处理，不强制反序列化。
    StripeObject object = event.getDataObjectDeserializer().getObject()
        .orElseThrow(() -> new IllegalStateException("Webhook API version is incompatible with the Stripe SDK"));
    if (!(object instanceof Session)) {
      throw new IllegalArgumentException("Expected a Checkout Session");
    }
    Session session = (Session) object;

    Order order = orders.find(session.getClientReferenceId());
    if (order == null)
      throw new IllegalArgumentException("Unknown order");
    // 验签确认“通知来自 Stripe、内容没被篡改”
    validate(order, session);
    String status = "PENDING_PAYMENT";
    String paymentStatus;
    if ("paid".equals(session.getPaymentStatus()) && "complete".equals(session.getStatus())) {
      status = "PAID";
      paymentStatus = "PAID";
    } else if ("expired".equals(session.getStatus())) {
      status = "EXPIRED";
      paymentStatus = "UNPAID";
    } else if ("checkout.session.async_payment_failed".equals(type)) {
      paymentStatus = "FAILED";
    } else if ("complete".equals(session.getStatus())) {
      paymentStatus = "PROCESSING";
    } else {
      paymentStatus = "UNPAID";
    }
    orders.applyEvent(event.getId(), order.id, session.getId(), session.getPaymentIntent(), status, paymentStatus);
  }

  private void validate(Order order, Session session) {
    if (session.getId() == null || !"payment".equals(session.getMode())
        || !order.id.equals(session.getClientReferenceId())
        || session.getMetadata() == null || !order.id.equals(session.getMetadata().get("orderNo"))
        || !order.currency.equals(session.getCurrency())
        || session.getAmountTotal() == null || order.amount != session.getAmountTotal()
        || (order.sessionId != null && !order.sessionId.equals(session.getId()))) {
      throw new IllegalArgumentException("Checkout does not match the local order");
    }
  }
}
