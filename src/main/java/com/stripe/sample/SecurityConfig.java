package com.stripe.sample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService userDetailsService(UserRepository users) {
    return email -> {
      UserRepository.Account account;
      try {
        account = users.findByEmail(email);
      } catch (java.sql.SQLException e) {
        // 记录可定位数据库错误的编码，不输出 SQL 参数或账号凭据。
        throw new InternalAuthenticationServiceException(
            "Unable to read account; SQLState=" + e.getSQLState() + ", code=" + e.getErrorCode());
      }
      if (account == null) throw new UsernameNotFoundException("Invalid credentials");
      // 认证后的 Principal 保存固定用户 ID，订单归属不受邮箱或浏览器变化影响。
      return User.withUsername(account.id()).password(account.passwordHash()).roles("USER").build();
    };
  }

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/orders", "/api/orders/**", "/create-checkout-session", "/api/me").authenticated()
        .anyRequest().permitAll())
        // Stripe 通过签名验证身份；浏览器的注册、登录、退出和付款请求仍须提供 CSRF token。
        .csrf(csrf -> csrf.ignoringRequestMatchers("/webhook"))
        .requestCache(cache -> cache.disable())
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, error) -> {
              response.setStatus(401);
              response.setContentType("application/json");
              response.getWriter().write("{\"error\":\"Login required\"}");
            })
            .accessDeniedHandler((request, response, error) -> {
              response.setStatus(403);
              response.setContentType("application/json");
              response.getWriter().write("{\"error\":\"Request verification failed; refresh and retry\"}");
            }))
        .formLogin(form -> form.loginPage("/login.html").loginProcessingUrl("/api/login")
            .usernameParameter("email")
            .successHandler((request, response, authentication) -> {
              response.setContentType("application/json");
              response.getWriter().write("{\"authenticated\":true}");
            })
            .failureHandler((request, response, error) -> {
              boolean unavailable = error instanceof InternalAuthenticationServiceException;
              response.setStatus(unavailable ? 503 : 401);
              response.setContentType("application/json;charset=UTF-8");
              response.getWriter().write(unavailable
                  ? "{\"error\":\"账号服务暂不可用，请检查数据库连接后重试。\"}"
                  : "{\"error\":\"邮箱或密码不正确。\"}");
            }).permitAll())
        .logout(logout -> logout.logoutUrl("/api/logout").invalidateHttpSession(true)
            .deleteCookies("JSESSIONID")
            .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)));
    return http.build();
  }
}
