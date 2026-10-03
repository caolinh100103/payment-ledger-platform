package com.payledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PayledgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayledgerApplication.class, args);
    }
}
