package com.mt.salesapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class MadhanTradersApplication {
    public static void main(String[] args) {
        SpringApplication.run(MadhanTradersApplication.class, args);
    }
}