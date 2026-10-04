package com.liu.eka.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * 跨域放行配置：EKA-UI 前后端分离开发，前端 dev 服务与后端不同源。
 * 必须以 Filter 层注册（而非 MVC 的 addCorsMappings）：鉴权过滤器
 * {@link com.liu.eka.filter.AuthFilter} 在未登录时于 Filter 层直接回 401，
 * 只有同样处于 Filter 层的 CorsFilter 才能在这些响应上补齐 CORS 头，
 * 否则浏览器会以"Failed to fetch"拦截，前端拿不到 401 也就无法跳登录页。
 * 过滤顺序设为 0，先于鉴权过滤器执行。
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Configuration
public class CorsConfig {

    /** 免登录期也会被访问，跨域头必须由 Filter 层统一补齐 */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        // 步骤 1：组装跨域规则，放行 EKA-UI 本地开发地址并允许携带 Cookie
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:5173", "http://127.0.0.1:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        // 步骤 2：规则注册到所有路径
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        // 步骤 3：以 Filter 形式注册，顺序 0 保证在鉴权过滤器之前执行
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(0);
        return registration;
    }
}
