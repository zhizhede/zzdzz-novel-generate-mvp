package com.zzdzz.novelgen;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.zzdzz.novelgen.dao")
public class NovelGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovelGenApplication.class, args);
    }
}
