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
// Spring 管理认证服务实例；密码用 BCrypt 校验，登录凭证采用服务端可撤销的随机令牌。
public class AuthService {
  private final BusinessRepository repo;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);
  private final SecureRandom random = new SecureRandom();
  private final String dummyHash = bcrypt.encode(UUID.randomUUID().toString());

  /**
   * 用途：注入用户与会话持久化依赖。
   * 输入：repo：业务仓储。
   * 输出：构造函数，无返回值；建立实例内部状态。
   */
  AuthService(BusinessRepository repo) {
    this.repo = repo;
  }

  /**
   * 用途：转换为不含密码的用户 DTO。
   * 输入：e：用户实体。
   * 输出：用户 ID、姓名、部门和角色。
   */
  public static User view(UserEntity e) {
    return new User(e.id, e.username, e.displayName, e.departmentId, e.role);
  }

  /**
   * 用途：验证密码并创建 12 小时有效的登录会话。
   * 输入：body：用户名和密码。
   * 输出：令牌与用户信息；数据库只保存令牌摘要。
   */
  public LoginResult login(Login body) {
    var found = repo.query(UserEntity.class, "where e.username=?1", body.username().trim());
    var user = found.isEmpty() ? null : found.getFirst();
    // 不存在的账号也执行一次 BCrypt，减少通过响应耗时猜测用户名是否存在的机会。
    boolean valid = bcrypt.matches(body.password(), user == null ? dummyHash : user.passwordHash);
    if (user == null || !valid) throw new Failure(401, "用户名或密码错误");
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    repo.tx(
        () -> {
          repo.delete("SessionEntity", "where e.expiresAt < ?1", Instant.now());
          SessionEntity s = new SessionEntity();
          // 浏览器收到原始令牌，数据库只存 SHA-256 摘要；这不是携带身份声明的 JWT。
          s.id = hash(token);
          s.userId = user.id;
          s.expiresAt = Instant.now().plusSeconds(43200);
          repo.save(s);
          return null;
        });
    return new LoginResult(token, view(user));
  }

  /**
   * 用途：验证会话有效期并加载当前身份。
   * 输入：token：原始登录令牌。
   * 输出：用户实体；无效或过期时 null。
   */
  public UserEntity authenticate(String token) {
    // 每次认证都从数据库读取当前用户，部门和角色不采信浏览器传来的值。
    if (token == null || token.length() > 256) return null;
    SessionEntity s = repo.find(SessionEntity.class, hash(token));
    return s != null && s.expiresAt.isAfter(Instant.now())
        ? repo.find(UserEntity.class, s.userId)
        : null;
  }

  /**
   * 用途：撤销登录凭证。
   * 输入：token：原始令牌。
   * 输出：void；删除会话摘要记录。
   */
  public void logout(String token) {
    repo.delete("SessionEntity", "where e.id=?1", hash(token));
  }

  /**
   * 用途：计算用于存储和查询令牌的摘要。
   * 输入：value：原始字符串。
   * 输出：SHA-256 十六进制摘要字符串。
   */
  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * 用途：按管理员或处理员权限列出用户。
   * 输入：actor：已认证用户。
   * 输出：允许查看的用户 DTO 列表；员工无权访问。
   */
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

  /**
   * 用途：管理员创建用户并散列密码。
   * 输入：actor：管理员；input：新用户信息。
   * 输出：新用户 DTO；写入数据库。
   */
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

  /**
   * 用途：提供首次启动的演示账号初始化任务。
   * 输入：env：环境中的初始账号密码配置。
   * 输出：ApplicationRunner；执行时仅补建缺失账号。
   */
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
