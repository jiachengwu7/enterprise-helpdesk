package com.example.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class HelpdeskApplication {
  public static void main(String[] args) {
    SpringApplication.run(HelpdeskApplication.class, args);
  }
}
