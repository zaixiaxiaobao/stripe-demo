package com.stripe.sample;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;

public class PaymentServiceTest {
  private static final String SECRET = "whsec_local_simulation_only";
  private OrderRepository orders;
  private FakeGateway gateway;
  private PaymentService service;
  private String owner;
  private String requestKey;
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Before public void setup() throws Exception {
    orders = new OrderRepository("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    gateway = new FakeGateway();
    service = new PaymentService(orders, gateway, "http://localhost:4242", SECRET);
    owner = UUID.randomUUID().toString();
    requestKey = UUID.randomUUID().toString();
  }

  @Test public void serverPriceAndOrderLinkAreUsed() throws Exception {
    Order order = service.checkout(owner, requestKey);
    assertEquals(2000, order.amount);
    assertEquals(order.id, gateway.params.getClientReferenceId());
    assertEquals(order.id, gateway.params.getMetadata().get("orderNo"));
    assertEquals(order.id, gateway.params.getPaymentIntentData().getMetadata().get("orderNo"));
    assertEquals(Long.valueOf(2000), gateway.params.getLineItems().get(0).getPriceData().getUnitAmount());
    assertEquals("usd", gateway.params.getLineItems().get(0).getPriceData().getCurrency());
    assertTrue(gateway.params.getSuccessUrl().contains("{CHECKOUT_SESSION_ID}"));
    assertEquals(3600, order.expiresAt - order.createdAt);
    assertEquals("checkout-" + order.id, gateway.options.getIdempotencyKey());
    assertEquals(1, gateway.creates);
  }

  @Test public void repeatedClickReusesOrderAndSession() throws Exception {
    Order first = service.checkout(owner, requestKey);
    Order second = service.checkout(owner, requestKey);
    assertEquals(first.id, second.id);
    assertEquals(first.sessionId, second.sessionId);
    assertEquals(1, gateway.creates);
  }

  @Test public void uncertainCreationRetainsStableIdempotencyAndParameters() throws Exception {
    gateway.failCreate = true;
    assertThrows(java.io.IOException.class, () -> service.checkout(owner, requestKey));
    String firstKey = gateway.options.getIdempotencyKey();
    Long firstExpiry = gateway.params.getExpiresAt();
    gateway.failCreate = false;
    service.checkout(owner, requestKey);
    assertEquals(firstKey, gateway.options.getIdempotencyKey());
    assertEquals(firstExpiry, gateway.params.getExpiresAt());
  }

  @Test public void anotherBrowserCannotReadOrder() throws Exception {
    Order order = service.checkout(owner, requestKey);
    assertNull(service.getOrder("other-browser", order.id));
    assertNotNull(service.getOrder(owner, order.id));
  }

  @Test public void completedAndPaidMarksOrderPaid() throws Exception {
    Order order = service.checkout(owner, requestKey);
    paid();
    deliver("evt_paid", "checkout.session.completed");
    Order result = orders.find(order.id);
    assertEquals("PAID", result.status);
    assertEquals("pi_local", result.paymentIntentId);
  }

  @Test public void completedUnpaidWaitsForAsyncSuccess() throws Exception {
    Order order = service.checkout(owner, requestKey);
    gateway.session.setStatus("complete");
    deliver("evt_processing", "checkout.session.completed");
    assertEquals("PROCESSING", orders.find(order.id).paymentStatus);
    assertEquals("PENDING_PAYMENT", orders.find(order.id).status);
    paid();
    deliver("evt_async", "checkout.session.async_payment_succeeded");
    assertEquals("PAID", orders.find(order.id).status);
  }

  @Test public void asyncFailureIsNotOrderCancellationOrSuccess() throws Exception {
    Order order = service.checkout(owner, requestKey);
    gateway.session.setStatus("complete");
    deliver("evt_failed", "checkout.session.async_payment_failed");
    assertEquals("FAILED", orders.find(order.id).paymentStatus);
    assertEquals("PENDING_PAYMENT", orders.find(order.id).status);
    deliver("evt_late_completed", "checkout.session.completed");
    assertEquals("FAILED", orders.find(order.id).paymentStatus);
  }

  @Test public void expiredSessionMarksUnpaidOrderExpired() throws Exception {
    Order order = service.checkout(owner, requestKey);
    gateway.session.setStatus("expired");
    deliver("evt_expired", "checkout.session.expired");
    assertEquals("EXPIRED", orders.find(order.id).status);
  }

  @Test public void duplicateConcurrentAndLateEventsCannotDowngradePaid() throws Exception {
    Order order = service.checkout(owner, requestKey);
    paid();
    String body = event("evt_duplicate", "checkout.session.completed");
    String signature = sign(body);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        futures.add(executor.submit(() -> {
          try { service.webhook(body, signature); }
          catch (Exception e) { throw new RuntimeException(e); }
        }));
      }
      for (Future<?> future : futures) future.get();
    } finally { executor.shutdownNow(); }
    gateway.session.setStatus("expired");
    gateway.session.setPaymentStatus("unpaid");
    deliver("evt_late_expired", "checkout.session.expired");
    assertEquals("PAID", orders.find(order.id).status);
  }

  @Test public void invalidSignatureNeverRetrievesSession() throws Exception {
    service.checkout(owner, requestKey);
    String body = event("evt_invalid", "checkout.session.completed");
    String signature = sign(body);
    assertThrows(SignatureVerificationException.class,
        () -> service.webhook(body + " ", signature));
    assertEquals(0, gateway.retrieves);
  }

  @Test public void mismatchIsRejectedAndSameEventCanBeRetriedAfterCorrection() throws Exception {
    Order order = service.checkout(owner, requestKey);
    paid();
    gateway.session.setAmountTotal(1L);
    assertThrows(IllegalArgumentException.class,
        () -> deliver("evt_retry", "checkout.session.completed"));
    assertEquals("PENDING_PAYMENT", orders.find(order.id).status);
    gateway.session.setAmountTotal(2000L);
    gateway.session.setCurrency("nzd");
    assertThrows(IllegalArgumentException.class,
        () -> deliver("evt_retry", "checkout.session.completed"));
    gateway.session.setCurrency("usd");
    gateway.session.setMetadata(Collections.singletonMap("orderNo", "wrong-order"));
    assertThrows(IllegalArgumentException.class,
        () -> deliver("evt_retry", "checkout.session.completed"));
    gateway.session.setMetadata(Collections.singletonMap("orderNo", order.id));
    deliver("evt_retry", "checkout.session.completed");
    assertEquals("PAID", orders.find(order.id).status);
  }

  @Test public void webhookCanArriveBeforeCreateResponseIsBound() throws Exception {
    Order order = orders.createOrGet(owner, requestKey);
    gateway.session = session(order.id);
    paid();
    deliver("evt_early", "checkout.session.completed");
    orders.bindSession(order.id, gateway.session.getId(), gateway.session.getUrl());
    assertEquals("PAID", orders.find(order.id).status);
  }

  @Test public void fileDatabaseSurvivesRepositoryReopen() throws Exception {
    Path path = temp.getRoot().toPath().resolve("orders");
    String url = "jdbc:h2:" + path.toAbsolutePath();
    OrderRepository first = new OrderRepository(url);
    Order order = first.createOrGet(owner, requestKey);
    first.bindSession(order.id, "cs_persisted", "https://checkout.stripe.com/local");
    first.applyEvent("evt_persisted", order.id, "cs_persisted", "pi_local", "PAID", "PAID");
    OrderRepository reopened = new OrderRepository(url);
    assertEquals("PAID", reopened.find(order.id).status);
    assertEquals(order.id, reopened.createOrGet(owner, requestKey).id);
  }

  @Test public void invalidRequestDoesNotReachStripe() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> service.checkout(owner, null));
    assertThrows(IllegalArgumentException.class, () -> service.checkout(owner, "bad"));
    assertEquals(0, gateway.creates);
  }

  private void paid() {
    gateway.session.setStatus("complete");
    gateway.session.setPaymentStatus("paid");
    gateway.session.setPaymentIntent("pi_local");
  }

  private void deliver(String id, String type) throws Exception {
    String body = event(id, type);
    service.webhook(body, sign(body));
  }

  private String event(String id, String type) {
    return "{\"id\":\"" + id + "\",\"object\":\"event\",\"api_version\":\"2026-09-30.endive\","
        + "\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"cs_local\","
        + "\"object\":\"checkout.session\",\"metadata\":{\"note\":\"本地验证\"}}}}";
  }

  private String sign(String body) throws Exception {
    long time = Instant.now().getEpochSecond();
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal((time + "." + body).getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : digest) hex.append(String.format("%02x", b & 255));
    return "t=" + time + ",v1=" + hex;
  }

  private static Session session(String id) {
    Session session = new Session();
    session.setId("cs_local");
    session.setMode("payment");
    session.setClientReferenceId(id);
    session.setMetadata(Collections.singletonMap("orderNo", id));
    session.setCurrency("usd");
    session.setAmountTotal(2000L);
    session.setStatus("open");
    session.setPaymentStatus("unpaid");
    session.setUrl("https://checkout.stripe.com/local-simulation");
    return session;
  }

  private static class FakeGateway implements PaymentService.Gateway {
    Session session;
    SessionCreateParams params;
    RequestOptions options;
    int creates;
    int retrieves;
    boolean failCreate;

    public Session create(SessionCreateParams params, RequestOptions options) throws Exception {
      this.params = params;
      this.options = options;
      creates++;
      if (failCreate) throw new java.io.IOException("Simulated response loss");
      session = session(params.getClientReferenceId());
      return session;
    }
    public Session retrieve(String id) {
      synchronized (this) { retrieves++; }
      assertEquals("cs_local", id);
      return session;
    }
  }
}
