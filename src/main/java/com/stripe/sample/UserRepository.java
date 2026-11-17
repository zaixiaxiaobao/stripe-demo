package com.stripe.sample;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** 本地账号和订单使用同一文件数据库；只保存密码哈希。 */
public final class UserRepository {
  public record Account(String id, String email, String passwordHash) {}
  private final String url;

  public UserRepository(String url) throws SQLException {
    this.url = url;
    try (Connection c = connect(); Statement s = c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS app_user (id VARCHAR(36) PRIMARY KEY, "
          + "email VARCHAR(254) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL, created_at BIGINT NOT NULL)");
    }
  }

  private Connection connect() throws SQLException {
    return DriverManager.getConnection(url, "sa", "");
  }

  public static String normalizeEmail(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  public Account findByEmail(String email) throws SQLException {
    return find("SELECT * FROM app_user WHERE email=?", normalizeEmail(email));
  }

  public Account findById(String id) throws SQLException {
    return find("SELECT * FROM app_user WHERE id=?", id);
  }

  private Account find(String sql, String value) throws SQLException {
    try (Connection c = connect(); PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, value);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? new Account(r.getString("id"), r.getString("email"), r.getString("password_hash")) : null;
      }
    }
  }

  public void create(String email, String passwordHash) throws SQLException {
    try (Connection c = connect(); PreparedStatement s = c.prepareStatement(
        "INSERT INTO app_user(id,email,password_hash,created_at) VALUES(?,?,?,?)")) {
      s.setString(1, UUID.randomUUID().toString());
      s.setString(2, normalizeEmail(email));
      s.setString(3, passwordHash);
      s.setLong(4, Instant.now().getEpochSecond());
      s.executeUpdate();
    }
  }
}
