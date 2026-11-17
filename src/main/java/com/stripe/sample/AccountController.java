package com.stripe.sample;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.sql.SQLException;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {
  private final UserRepository users;
  private final PasswordEncoder passwords;

  public AccountController(UserRepository users, PasswordEncoder passwords) {
    this.users = users;
    this.passwords = passwords;
  }

  @GetMapping("/api/csrf")
  public Map<String, String> csrf(CsrfToken csrf) {
    return Map.of("headerName", csrf.getHeaderName(), "token", csrf.getToken());
  }

  @GetMapping("/api/me")
  public ResponseEntity<?> me(Principal principal) throws SQLException {
    UserRepository.Account account = users.findById(principal.getName());
    if (account == null) return ResponseEntity.status(401).body(Map.of("error", "Login required"));
    return ResponseEntity.ok(Map.of("id", account.id(), "email", account.email()));
  }

  @PostMapping("/api/register")
  public ResponseEntity<?> register(@RequestParam String email, @RequestParam String password) throws SQLException {
    String normalized = UserRepository.normalizeEmail(email);
    if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
      return ResponseEntity.badRequest().body(Map.of("error", "请输入有效邮箱地址。"));
    }
    if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      return ResponseEntity.badRequest().body(Map.of("error", "密码至少 8 个字符，且 UTF-8 长度不超过 72 字节。"));
    }
    try {
      users.create(normalized, passwords.encode(password));
    } catch (SQLException e) {
      if ("23505".equals(e.getSQLState())) {
        return ResponseEntity.status(409).body(Map.of("error", "该邮箱已注册，请直接登录。"));
      }
      throw e;
    }
    return ResponseEntity.status(201).body(Map.of("registered", true));
  }
}
