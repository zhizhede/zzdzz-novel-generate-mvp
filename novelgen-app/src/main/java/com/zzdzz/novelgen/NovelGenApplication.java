package com.zzdzz.novelgen;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

@SpringBootApplication
@ConfigurationPropertiesScan
// derive.mapper 与 mapper 存在同名接口（ChapterMapper/NovelMapper…），bean 名用 FQCN 防撞
// （与 DeriveGenApplication 同款；全仓注入均按类型，0 处 @Resource/@Qualifier）
@MapperScan(basePackages = {"com.zzdzz.novelgen.mapper", "com.zzdzz.novelgen.derive.mapper"},
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class NovelGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(NovelGenApplication.class, args);
    }
}
