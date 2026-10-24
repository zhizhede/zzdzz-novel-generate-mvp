package com.zzdzz.novelgen.derive;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * 随机衍生系统启动壳（架构设计 D11/F 方案，2026-10-12 拍板）。
 *
 * <p>扫描白名单（只装 derive + 共享基建，zzdzz 业务类一律不进容器）：
 * <ul>
 *   <li>{@code derive} —— 衍生业务包（各模块内未来统一放 com.zzdzz.novelgen.derive.*）</li>
 *   <li>{@code service.data} —— 共享数据服务（UserDataService/LlmCallLogDataService 等，纯 DAO 无监听器）</li>
 *   <li>{@code llm} —— LLM 客户端与接入（MiniMaxClient/LlmProviderResolver/LlmJson…；
 *       LlmJson 依赖的 PromptTemplateService 由 DeriveBootConfig 手动 @Bean 注册，V104 已建表）</li>
 *   <li>{@code common.web} —— JwtService（common）+ 本壳拷贝的 AuthInterceptor/GlobalExceptionHandler</li>
 *   <li>{@code controller} —— 本壳拷贝的 AuthController（zzdzz app 模块不在 classpath，无同名冲突）</li>
 * </ul>
 *
 * <p>不扫 {@code com.zzdzz.novelgen.service} 业务包的原因：GenerationQueueService/ChapterPipelineService/
 * SampleParseService/OutlineDraftService/PromptTemplateService 全带 ApplicationReady 监听（写
 * generation_tasks/prompt_templates 等 zzdzz 表），且新旧 novels 同名不同构——zzdzz 表在 derive_gen
 * 根本不存在，加载即崩。AuthService 无监听器但同包，由 DeriveBootConfig 手动 @Bean 注册。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ConfigurationPropertiesScan({"com.zzdzz.novelgen.llm", "com.zzdzz.novelgen.derive"})
// nameGenerator 用 FQCN：derive.mapper.NovelMapper 与 zzdzz mapper.NovelMapper 简单类名相同，
// 默认按简单类名命名 bean 会撞（ConflictingBeanDefinitionException）。全仓注入均按类型（0 处
// @Resource/@Qualifier），改名零风险；也顺带免疫未来 derive ChapterMapper 与 zzdzz 的同名撞。
@MapperScan(basePackages = {"com.zzdzz.novelgen.mapper", "com.zzdzz.novelgen.derive.mapper"},
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
@ComponentScan(
        basePackages = {
                "com.zzdzz.novelgen.derive",
                "com.zzdzz.novelgen.service.data",
                "com.zzdzz.novelgen.llm",
                "com.zzdzz.novelgen.common.web",
                "com.zzdzz.novelgen.controller"
        }
)
public class DeriveGenApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeriveGenApplication.class, args);
    }
}
