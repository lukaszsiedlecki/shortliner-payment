package com.shortliner.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ShortlinerPaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortlinerPaymentApplication.class, args);
    }
}
