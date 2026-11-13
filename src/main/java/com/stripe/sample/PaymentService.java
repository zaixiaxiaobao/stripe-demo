package com.stripe.sample;

import java.time.Instant;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.stripe.StripeClient;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;

public final class PaymentService {
  interface Gateway {
    Session create(SessionCreateParams params, RequestOptions options) throws Exception;
    Session retrieve(String sessionId) throws Exception;
  }

  static Gateway stripeGateway(StripeClient client) {
    return new Gateway() {
      public Session create(SessionCreateParams params, RequestOptions options) throws Exception {
        return client.v1().checkout().sessions().create(params, options);
      }
      public Session retrieve(String id) throws Exception {
        return client.v1().checkout().sessions().retrieve(id);
      }
    };
  }

  private final OrderRepository orders;
  private final Gateway gateway;
  private final String baseUrl;
  private final String webhookSecret;

  PaymentService(OrderRepository orders, Gateway gateway, String baseUrl, String webhookSecret) {
    this.orders = orders;
    this.gateway = gateway;
    this.baseUrl = baseUrl;
    this.webhookSecret = webhookSecret;
  }

  public Order checkout(String owner, String requestKey) throws Exception {
    if (requestKey == null || !requestKey.matches("[0-9a-fA-F-]{36}")) {
      throw new IllegalArgumentException("A UUID requestKey is required");
    }
    UUID.fromString(requestKey);
    Order order = orders.createOrGet(owner, requestKey);
    if (!"PENDING_PAYMENT".equals(order.status)) return order;
    if (order.sessionId != null) return order;
    // Stripe requires at least 30 minutes until expires_at on initial creation.
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
        .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
            .putMetadata("orderNo", order.id).build())
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
    // Stable per order, including retries after an uncertain network response.
    Session session = gateway.create(params,
        RequestOptions.builder().setIdempotencyKey("checkout-" + order.id).build());
    validate(order, session);
    if (session.getUrl() == null) throw new IllegalStateException("Missing Checkout URL");
    orders.bindSession(order.id, session.getId(), session.getUrl());
    return orders.find(order.id);
  }

  public Order getOrder(String owner, String id) throws Exception {
    Order order = orders.find(id);
    return order != null && order.owner.equals(owner) ? order : null;
  }

  public void webhook(String rawBody, String signature) throws Exception {
    Event event = Webhook.constructEvent(rawBody, signature, webhookSecret);
    String type = event.getType();
    if (!"checkout.session.completed".equals(type)
        && !"checkout.session.async_payment_succeeded".equals(type)
        && !"checkout.session.async_payment_failed".equals(type)
        && !"checkout.session.expired".equals(type)) return;
    // Only use the signed object's ID, then retrieve the latest state with our API credentials.
    // Avoid unsafe deserialization when event and SDK API versions differ.
    JsonObject object = JsonParser.parseString(event.getDataObjectDeserializer().getRawJson()).getAsJsonObject();
    if (!"checkout.session".equals(object.get("object").getAsString())) {
      throw new IllegalArgumentException("Expected a Checkout Session");
    }
    Session session = gateway.retrieve(object.get("id").getAsString());
    Order order = orders.find(session.getClientReferenceId());
    if (order == null) throw new IllegalArgumentException("Unknown order");
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
