package com.zzdzz.novelgen.derive.config;

import com.zzdzz.novelgen.common.web.AuthInterceptor;
import com.zzdzz.novelgen.common.web.JwtService;
import com.zzdzz.novelgen.common.web.SessionStore;
import com.zzdzz.novelgen.llm.LlmProperties;
import com.zzdzz.novelgen.service.AuthService;
import com.zzdzz.novelgen.service.PromptTemplateService;
import com.zzdzz.novelgen.service.data.PromptTemplateDataService;
import com.zzdzz.novelgen.service.data.UserDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.http.HttpClient;

/**
 * 衍生壳 Web/LLM 装配（拷贝自 novelgen-app 的 WebConfig + LlmClientConfig，职责等价）：
 * JWT 登录拦截 + Vue 开发服务器跨域 + 共享 HttpClient。
 * AuthService/PromptTemplateService 手动注册——它们在 com.zzdzz.novelgen.service 包
 * （与 zzdzz 业务服务同包，不能整包扫描），但都无启动监听器副作用：
 * AuthService 纯鉴权；PromptTemplateService 的 ApplicationReady 同步写 prompt_templates
 * （V104 已建表，且 LlmJson 的 JSON 重试反馈依赖它），注册后同步照常工作。
 */
@Configuration
@RequiredArgsConstructor
public class DeriveBootConfig implements WebMvcConfigurer {

    private final JwtService jwtService;
    private final SessionStore sessionStore;


    @Bean
    public AuthService authService(UserDataService userData) {
        return new AuthService(userData);
    }

    /** 提示词注册表服务（LlmJson 依赖；启动同步落库 V104）。 */
    @Bean
    public PromptTemplateService promptTemplateService(PromptTemplateDataService dao) {
        return new PromptTemplateService(dao);
    }

    @Bean
    public AuthInterceptor authInterceptor() {
        return new AuthInterceptor(jwtService, sessionStore);
    }

    /** LLM HTTP 客户端装配：共享 HttpClient；接入要素每次调用由 LlmProviderResolver 解析（同 zzdzz 口径）。 */
    @Bean
    public HttpClient llmHttpClient(LlmProperties props) {
        return HttpClient.newBuilder()
                .connectTimeout(props.connectTimeout())
                .build();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowCredentials(true);
    }
}
