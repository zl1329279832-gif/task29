package com.visitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VisitorManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(VisitorManagementApplication.class, args);
    }
}
