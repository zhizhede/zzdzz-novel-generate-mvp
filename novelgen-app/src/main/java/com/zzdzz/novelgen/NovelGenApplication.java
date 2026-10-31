package com.zzdzz.novelgen;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan(basePackages = {"com.zzdzz.novelgen.mapper", "com.zzdzz.novelgen.derive.mapper"},
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class NovelGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovelGenApplication.class, args);
    }
}
