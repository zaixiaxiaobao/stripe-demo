package com.stripe.sample;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

/** File-backed H2 is intended for this single-process demo, not a shared production database. */
public final class OrderRepository {
  private final String url;

  public OrderRepository(String url) throws SQLException {
    this.url = url;
    try (Connection c = connect(); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS payment_order ("
          + "id VARCHAR(36) PRIMARY KEY, owner VARCHAR(36) NOT NULL, request_key VARCHAR(36) NOT NULL, "
          + "product_name VARCHAR(255) NOT NULL, quantity BIGINT NOT NULL, unit_amount BIGINT NOT NULL, "
          + "amount BIGINT NOT NULL, currency VARCHAR(3) NOT NULL, status VARCHAR(32) NOT NULL, "
          + "payment_status VARCHAR(32) NOT NULL, session_id VARCHAR(255) UNIQUE, checkout_url VARCHAR(4096), "
          + "payment_intent_id VARCHAR(255), created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, "
          + "UNIQUE(owner, request_key))");
      s.execute("CREATE TABLE IF NOT EXISTS payment_event (id VARCHAR(255) PRIMARY KEY, "
          + "order_id VARCHAR(36) NOT NULL, processed_at BIGINT NOT NULL)");
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(url, "sa", "");
  }

  public synchronized Order createOrGet(String owner, String requestKey) throws SQLException {
    try (Connection c = connect()) {
      try (PreparedStatement s = c.prepareStatement(
          "SELECT * FROM payment_order WHERE owner=? AND request_key=?")) {
        s.setString(1, owner);
        s.setString(2, requestKey);
        try (ResultSet r = s.executeQuery()) {
          if (r.next()) return read(r);
        }
      }
      long now = Instant.now().getEpochSecond();
      String id = UUID.randomUUID().toString();
      try (PreparedStatement s = c.prepareStatement("INSERT INTO payment_order "
          + "(id,owner,request_key,product_name,quantity,unit_amount,amount,currency,status,payment_status,created_at,expires_at) "
          + "VALUES (?,?,?,'Stubborn Attachments',1,2000,2000,'usd','PENDING_PAYMENT','UNPAID',?,?)")) {
        s.setString(1, id);
        s.setString(2, owner);
        s.setString(3, requestKey);
        s.setLong(4, now);
        s.setLong(5, now + 3600);
        s.executeUpdate();
      }
      return find(c, id, false);
    }
  }

  public Order find(String id) throws SQLException {
    try (Connection c = connect()) { return find(c, id, false); }
  }

  private Order find(Connection c, String id, boolean lock) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(
        "SELECT * FROM payment_order WHERE id=?" + (lock ? " FOR UPDATE" : ""))) {
      s.setString(1, id);
      try (ResultSet r = s.executeQuery()) { return r.next() ? read(r) : null; }
    }
  }

  public void bindSession(String id, String sessionId, String checkoutUrl) throws SQLException {
    try (Connection c = connect()) {
      try (PreparedStatement s = c.prepareStatement("UPDATE payment_order SET session_id=?,checkout_url=? "
          + "WHERE id=? AND (session_id IS NULL OR session_id=?)")) {
        s.setString(1, sessionId);
        s.setString(2, checkoutUrl);
        s.setString(3, id);
        s.setString(4, sessionId);
        if (s.executeUpdate() != 1) throw new IllegalStateException("Session binding conflict");
      }
    }
  }

  /** Event receipt and payment transition commit together; PAID is never downgraded. */
  public void applyEvent(String eventId, String id, String sessionId, String paymentIntentId,
      String status, String paymentStatus) throws SQLException {
    try (Connection c = connect()) {
      c.setAutoCommit(false);
      try {
        Order order = find(c, id, true);
        if (order == null) throw new IllegalArgumentException("Unknown order");
        if (order.sessionId != null && !sessionId.equals(order.sessionId)) {
          throw new IllegalArgumentException("Session mismatch");
        }
        try (PreparedStatement s = c.prepareStatement("SELECT id FROM payment_event WHERE id=?")) {
          s.setString(1, eventId);
          try (ResultSet r = s.executeQuery()) {
            if (r.next()) { c.rollback(); return; }
          }
        }
        boolean regressesTerminalState = !"PAID".equals(status)
            && (("EXPIRED".equals(order.status) && !"EXPIRED".equals(status))
                || ("FAILED".equals(order.paymentStatus) && "PROCESSING".equals(paymentStatus)));
        if (!"PAID".equals(order.status) && !regressesTerminalState) {
          try (PreparedStatement s = c.prepareStatement("UPDATE payment_order SET session_id=?, "
              + "payment_intent_id=?,status=?,payment_status=? WHERE id=?")) {
            s.setString(1, sessionId);
            s.setString(2, paymentIntentId);
            s.setString(3, status);
            s.setString(4, paymentStatus);
            s.setString(5, id);
            s.executeUpdate();
          }
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO payment_event VALUES (?,?,?)")) {
          s.setString(1, eventId);
          s.setString(2, id);
          s.setLong(3, Instant.now().getEpochSecond());
          s.executeUpdate();
        }
        c.commit();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private Order read(ResultSet r) throws SQLException {
    Order o = new Order();
    o.id = r.getString("id");
    o.owner = r.getString("owner");
    o.productName = r.getString("product_name");
    o.quantity = r.getLong("quantity");
    o.unitAmount = r.getLong("unit_amount");
    o.amount = r.getLong("amount");
    o.currency = r.getString("currency");
    o.status = r.getString("status");
    o.paymentStatus = r.getString("payment_status");
    o.sessionId = r.getString("session_id");
    o.checkoutUrl = r.getString("checkout_url");
    o.paymentIntentId = r.getString("payment_intent_id");
    o.createdAt = r.getLong("created_at");
    o.expiresAt = r.getLong("expires_at");
    return o;
  }
}
