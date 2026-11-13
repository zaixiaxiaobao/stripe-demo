package com.stripe.sample;

import static spark.Spark.*;

import java.net.URI;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;

import spark.Request;

public class Server {
  private static final Gson JSON = new Gson();

  public static void main(String[] args) throws Exception {
    String apiKey = requiredEnv("STRIPE_API_KEY");
    String webhookSecret = requiredEnv("STRIPE_WEBHOOK_SECRET");
    String baseUrl = env("APP_BASE_URL", "http://localhost:4242").replaceAll("/+$", "");
    URI base = URI.create(baseUrl);
    if (base.getHost() == null || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()))) {
      throw new IllegalArgumentException("APP_BASE_URL must be an HTTP or HTTPS origin");
    }
    OrderRepository orders = new OrderRepository(env("ORDER_DB_URL", "jdbc:h2:./data/orders"));
    PaymentService payments = new PaymentService(orders,
        PaymentService.stripeGateway(new StripeClient(apiKey)), baseUrl, webhookSecret);
    port(4242);
    staticFiles.externalLocation(Paths.get("public").toAbsolutePath().toString());
    before((request, response) -> {
      response.header("Cache-Control", "no-store");
      response.header("X-Content-Type-Options", "nosniff");
    });
    exception(Exception.class, (error, request, response) -> {
      // Never log request payloads, credentials or full Stripe objects.
      System.err.println("Request failed: " + error.getClass().getSimpleName());
      response.status(500);
      response.type("application/json");
      response.body("{\"error\":\"Request failed; retry with the same request key\"}");
    });

    post("/create-checkout-session", (request, response) -> {
      String origin = request.headers("Origin");
      if (origin != null && !baseUrl.equals(origin)) halt(403, "Origin not allowed");
      response.type("application/json");
      Order order;
      try {
        order = payments.checkout(owner(request), request.queryParams("requestKey"));
      } catch (IllegalArgumentException e) {
        halt(400, "Invalid checkout request");
        return "";
      } catch (IllegalStateException e) {
        halt(409, "Checkout unavailable; start a new purchase");
        return "";
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("orderId", order.id);
      result.put("status", order.status);
      result.put("url", "PAID".equals(order.status)
          ? "/success.html?order_id=" + order.id : order.checkoutUrl);
      return JSON.toJson(result);
    });

    get("/api/orders/:id", (request, response) -> {
      response.type("application/json");
      String owner = existingOwner(request);
      Order order = owner == null ? null : payments.getOrder(owner, request.params(":id"));
      if (order == null) { halt(404, "Order not found"); return ""; }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("orderId", order.id);
      result.put("productName", order.productName);
      result.put("quantity", order.quantity);
      result.put("amount", order.amount);
      result.put("currency", order.currency);
      result.put("status", order.status);
      result.put("paymentStatus", order.paymentStatus);
      return JSON.toJson(result);
    });

    post("/webhook", (request, response) -> {
      String signature = request.headers("Stripe-Signature");
      if (signature == null) { halt(400, "Missing signature"); return ""; }
      try {
        payments.webhook(request.body(), signature);
      } catch (SignatureVerificationException | IllegalArgumentException | com.google.gson.JsonParseException e) {
        halt(400, "Invalid webhook");
        return "";
      }
      response.type("application/json");
      return "{\"received\":true}";
    });
  }

  private static String owner(Request request) {
    String value = existingOwner(request);
    if (value == null) {
      value = UUID.randomUUID().toString();
      request.session(true).attribute("owner", value);
    }
    return value;
  }

  private static String existingOwner(Request request) {
    return request.session(false) == null ? null : request.session(false).attribute("owner");
  }

  private static String requiredEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.trim().isEmpty()) throw new IllegalStateException("Missing environment variable: " + name);
    return value;
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.trim().isEmpty() ? fallback : value;
  }
}
