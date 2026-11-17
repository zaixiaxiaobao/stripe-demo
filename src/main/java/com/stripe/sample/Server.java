package com.stripe.sample;

import java.net.URI;

import com.stripe.StripeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Server {
  public static void main(String[] args) {
    SpringApplication.run(Server.class, args);
  }

  @Bean
  OrderRepository orderRepository(@Value("${app.order-db-url}") String dbUrl) throws Exception {
    return new OrderRepository(dbUrl);
  }

  @Bean
  UserRepository userRepository(@Value("${app.order-db-url}") String dbUrl) throws Exception {
    return new UserRepository(dbUrl);
  }

  @Bean
  StripeClient stripeClient(@Value("${app.stripe-api-key}") String apiKey) {
    if (apiKey.isBlank()) {
      throw new IllegalStateException("STRIPE_API_KEY is required");
    }
    return new StripeClient(apiKey);
  }

  @Bean
  PaymentService paymentService(OrderRepository orders, StripeClient client,
      @Value("${app.stripe-webhook-secret}") String webhookSecret,
      @Value("${app.base-url}") String baseUrl) {
    if (webhookSecret.isBlank()) {
      throw new IllegalStateException("STRIPE_WEBHOOK_SECRET is required");
    }
    URI base = URI.create(baseUrl);
    if (base.getHost() == null || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()))) {
      throw new IllegalArgumentException("APP_BASE_URL must be an HTTP or HTTPS origin");
    }
    return new PaymentService(orders, client,
        baseUrl.replaceAll("/+$", ""), webhookSecret);
  }
}
