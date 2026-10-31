package com.example.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class HelpdeskApplication {
  /**
   * 用途：启动 Spring Boot 业务服务。
   * 输入：args：命令行参数。
   * 输出：void；创建应用上下文并启动服务。
   */
  public static void main(String[] args) {
    SpringApplication.run(HelpdeskApplication.class, args);
  }
}
