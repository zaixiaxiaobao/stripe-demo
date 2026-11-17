package com.stripe.sample;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.security.Principal;

import com.google.gson.JsonParseException;
import com.stripe.exception.SignatureVerificationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
  private final PaymentService payments;
  private final String baseUrl;

  public PaymentController(PaymentService payments, @Value("${app.base-url}") String baseUrl) {
    this.payments = payments;
    this.baseUrl = baseUrl.replaceAll("/+$", "");
  }

  @PostMapping("/create-checkout-session")
  public ResponseEntity<?> checkout(Principal principal,
      @RequestHeader(value = "Origin", required = false) String origin,
      @RequestParam(required = false) String requestKey) throws Exception {
    if (origin != null && !baseUrl.equals(origin)) {
      return error(HttpStatus.FORBIDDEN, "Origin not allowed");
    }
    Order order;
    try {
      order = payments.checkout(principal.getName(), requestKey);
    } catch (IllegalArgumentException e) {
      return error(HttpStatus.BAD_REQUEST, "Invalid checkout request");
    } catch (IllegalStateException e) {
      return error(HttpStatus.CONFLICT, "Checkout unavailable; start a new purchase");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("orderId", order.id);
    result.put("status", order.status);
    result.put("url", "PAID".equals(order.status)
        ? "/success.html?order_id=" + order.id : order.checkoutUrl);
    return ResponseEntity.ok(result);
  }

  @GetMapping("/api/orders")
  public ResponseEntity<?> orders(Principal principal,
      @RequestParam(defaultValue = "0") int page) throws Exception {
    if (page < 0 || page > 100000) return error(HttpStatus.BAD_REQUEST, "Invalid page");
    List<Order> orders = payments.listOrders(principal.getName(), page);
    return ResponseEntity.ok(Map.of(
        "orders", orders.stream().limit(20).map(PaymentController::orderResponse).toList(),
        "page", page,
        "hasMore", orders.size() > 20));
  }

  @GetMapping("/api/orders/{id}")
  public ResponseEntity<?> order(Principal principal, @PathVariable String id) throws Exception {
    Order order = payments.getOrder(principal.getName(), id);
    if (order == null) {
      return error(HttpStatus.NOT_FOUND, "Order not found");
    }
    return ResponseEntity.ok(orderResponse(order));
  }

  private static Map<String, Object> orderResponse(Order order) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("orderId", order.id);
    result.put("productName", order.productName);
    result.put("quantity", order.quantity);
    result.put("amount", order.amount);
    result.put("currency", order.currency);
    result.put("status", order.status);
    result.put("paymentStatus", order.paymentStatus);
    result.put("createdAt", order.createdAt);
    return result;
  }

  @PostMapping("/webhook")
  public ResponseEntity<?> webhook(@RequestBody(required = false) byte[] rawBody,
      @RequestHeader(value = "Stripe-Signature", required = false) String signature) throws Exception {
    if (signature == null) {
      return error(HttpStatus.BAD_REQUEST, "Missing signature");
    }
    if (rawBody == null || rawBody.length == 0) {
      return error(HttpStatus.BAD_REQUEST, "Invalid webhook");
    }
    try {
      // 原始请求字节以 UTF-8 解码；先解析 JSON 再序列化会导致签名改变。
      payments.webhook(new String(rawBody, StandardCharsets.UTF_8), signature);
    } catch (SignatureVerificationException | IllegalArgumentException | JsonParseException e) {
      return error(HttpStatus.BAD_REQUEST, "Invalid webhook");
    }
    return ResponseEntity.ok(Map.of("received", true));
  }

  private static ResponseEntity<?> error(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(Map.of("error", message));
  }

}
