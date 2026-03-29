package com.biometric.poc;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.biometric.poc.mapper")
public class BiometricAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(BiometricAuthApplication.class, args);
    }
}
