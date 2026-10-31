package com.example.helpdesk;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
  /**
   * 用途：禁用默认用户名密码认证入口。
   * 输入：无参数。
   * 输出：始终拒绝用户名查询的 UserDetailsService。
   */
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noBasicLogin() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Only opaque bearer authentication is supported");
    };
  }

  /**
   * 用途：配置令牌认证、跨域与统一认证错误响应。
   * 输入：http：安全配置器；auth：认证服务。
   * 输出：SecurityFilterChain。
   */
  @Bean
  SecurityFilterChain chain(HttpSecurity http, AuthService auth) throws Exception {
    // STATELESS 指不使用 Servlet HttpSession；本项目仍在数据库中保存自己的令牌会话。
    // 认证使用显式 Authorization 请求头，不依赖浏览器自动携带的登录 Cookie。
    http.csrf(c -> c.disable())
        .cors(c -> c.configurationSource(cors()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers("/api/health", "/api/auth/login")
                .permitAll()
                .anyRequest()
                .authenticated());
    http.exceptionHandling(
        e ->
            e.authenticationEntryPoint((req, res, ex) -> error(res, 401, "请先登录"))
                .accessDeniedHandler((req, res, ex) -> error(res, 403, "没有访问权限")));
    http.addFilterBefore(
        new OncePerRequestFilter() {
          /**
           * 用途：解析 Bearer 令牌并写入当前请求身份。
           * 输入：req、res：请求和响应；next：后续过滤链。
           * 输出：void；设置 SecurityContext 后继续过滤链。
           */
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain next)
              throws ServletException, IOException {
            String header = req.getHeader("Authorization");
            // 先验证令牌，再把数据库中的用户放进 SecurityContext，供 Controller 取得 actor。
            if (header != null && header.startsWith("Bearer ")) {
              var user = auth.authenticate(header.substring(7));
              if (user != null)
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                            user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role))));
            }
            next.doFilter(req, res);
          }
        },
        UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * 用途：限定允许访问本机 API 的前端来源。
   * 输入：无参数。
   * 输出：按 /api/** 注册的跨域配置源。
   */
  private CorsConfigurationSource cors() {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://127.0.0.1:5180", "http://localhost:5180"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setExposedHeaders(List.of("Content-Disposition"));
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }

  /**
   * 用途：直接写入认证失败的统一 JSON。
   * 输入：res：响应；status：HTTP 状态；message：安全提示。
   * 输出：void；设置状态、编码并写响应正文。
   */
  static void error(HttpServletResponse res, int status, String message) throws IOException {
    res.setStatus(status);
    res.setCharacterEncoding("UTF-8");
    res.setContentType("application/json");
    res.getWriter()
        .write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
  }
}
