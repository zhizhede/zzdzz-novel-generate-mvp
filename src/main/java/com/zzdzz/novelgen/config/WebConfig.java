package com.zzdzz.novelgen.config;

import com.zzdzz.novelgen.common.web.AuthInterceptor;
import com.zzdzz.novelgen.common.web.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 层装配：JWT 登录拦截 + Vue 开发服务器跨域（生产走 Nginx 同源，不需要 CORS）。 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtService jwtService;

    public WebConfig(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Bean
    public AuthInterceptor authInterceptor() {
        return new AuthInterceptor(jwtService);
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
