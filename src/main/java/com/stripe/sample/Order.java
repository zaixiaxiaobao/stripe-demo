package com.stripe.sample;

public final class Order {
  public String id;
  String userId;
  public String productName;
  public long quantity;
  public long unitAmount;
  public long amount;
  public String currency;
  public String status;
  public String paymentStatus;
  public String sessionId;
  String checkoutUrl;
  public String paymentIntentId;
  public long createdAt;
  public long expiresAt;
}
