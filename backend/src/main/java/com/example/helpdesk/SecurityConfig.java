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
  @Bean
  org.springframework.security.core.userdetails.UserDetailsService noBasicLogin() {
    return username -> {
      throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
          "Only opaque bearer authentication is supported");
    };
  }

  @Bean
  SecurityFilterChain chain(HttpSecurity http, AuthService auth) throws Exception {
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
          @Override
          protected void doFilterInternal(
              HttpServletRequest req, HttpServletResponse res, FilterChain next)
              throws ServletException, IOException {
            String header = req.getHeader("Authorization");
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

  static void error(HttpServletResponse res, int status, String message) throws IOException {
    res.setStatus(status);
    res.setCharacterEncoding("UTF-8");
    res.setContentType("application/json");
    res.getWriter()
        .write("{\"code\":" + status + ",\"message\":\"" + message + "\",\"data\":null}");
  }
}
