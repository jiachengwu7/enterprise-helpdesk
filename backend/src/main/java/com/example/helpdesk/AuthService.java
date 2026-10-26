package com.example.helpdesk;

import static com.example.helpdesk.Api.*;
import static com.example.helpdesk.Entities.*;
import static com.example.helpdesk.Failures.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
  private final BusinessRepository repo;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
  private final SecureRandom random = new SecureRandom();
  private final String dummyHash = bcrypt.encode(UUID.randomUUID().toString());

  AuthService(BusinessRepository repo) {
    this.repo = repo;
  }

  public static User view(UserEntity e) {
    return new User(e.id, e.username, e.displayName, e.departmentId, e.role);
  }

  public LoginResult login(Login body) {
    var found = repo.query(UserEntity.class, "where e.username=?1", body.username().trim());
    var user = found.isEmpty() ? null : found.getFirst();
    boolean valid = bcrypt.matches(body.password(), user == null ? dummyHash : user.passwordHash);
    if (user == null || !valid) throw new Failure(401, "用户名或密码错误");
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    repo.tx(
        () -> {
          repo.delete("SessionEntity", "where e.expiresAt < ?1", Instant.now());
          SessionEntity s = new SessionEntity();
          s.id = hash(token);
          s.userId = user.id;
          s.expiresAt = Instant.now().plusSeconds(43200);
          repo.save(s);
          return null;
        });
    return new LoginResult(token, view(user));
  }

  public UserEntity authenticate(String token) {
    if (token == null || token.length() > 256) return null;
    SessionEntity s = repo.find(SessionEntity.class, hash(token));
    return s != null && s.expiresAt.isAfter(Instant.now())
        ? repo.find(UserEntity.class, s.userId)
        : null;
  }

  public void logout(String token) {
    repo.delete("SessionEntity", "where e.id=?1", hash(token));
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<User> users(UserEntity actor) {
    if (actor.role == Role.EMPLOYEE) throw forbidden();
    return repo
        .query(
            UserEntity.class,
            actor.role == Role.ADMIN
                ? "order by e.displayName"
                : "where e.departmentId=?1 order by e.displayName",
            actor.role == Role.ADMIN ? new Object[] {} : new Object[] {actor.departmentId})
        .stream()
        .map(AuthService::view)
        .toList();
  }

  public User create(UserEntity actor, NewUser input) {
    if (actor.role != Role.ADMIN) throw forbidden();
    if (input.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw bad("密码 UTF-8 长度不能超过 72 字节");
    return repo.tx(
        () -> {
          if (!repo.query(UserEntity.class, "where e.username=?1", input.username()).isEmpty())
            throw conflict("用户名已存在");
          UserEntity u = new UserEntity();
          u.username = input.username();
          u.displayName = input.displayName().trim();
          u.departmentId = input.departmentId();
          u.role = input.role();
          u.passwordHash = bcrypt.encode(input.password());
          return view(repo.save(u));
        });
  }

  @Bean
  ApplicationRunner seed(Environment env) {
    return _ -> {
      String[] names = {"admin", "agent", "employee", "hr"};
      String[] display = {"系统管理员", "IT 服务专员", "IT 员工", "HR 员工"};
      Role[] roles = {Role.ADMIN, Role.AGENT, Role.EMPLOYEE, Role.EMPLOYEE};
      for (int i = 0; i < names.length; i++) {
        final int n = i;
        repo.tx(
            () -> {
              if (repo.query(UserEntity.class, "where e.username=?1", names[n]).isEmpty()) {
                String password = env.getProperty("app.seed." + names[n], "");
                if (password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
                  throw new IllegalStateException(
                      "Initial password missing or outside 12..72-byte policy for " + names[n]);
                UserEntity u = new UserEntity();
                u.username = names[n];
                u.displayName = display[n];
                u.departmentId = n == 3 ? "HR" : "IT";
                u.role = roles[n];
                u.passwordHash = bcrypt.encode(password);
                repo.save(u);
              }
              return null;
            });
      }
    };
  }
}
