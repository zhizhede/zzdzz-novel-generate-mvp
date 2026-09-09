package com.zzdzz.novelgen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NovelGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovelGenApplication.class, args);
    }
}
