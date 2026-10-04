package com.liu.eka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.mybatis.spring.annotation.MapperScan;

/**
 * 企业知识库智能体（EKA）的应用启动入口，
 * 负责拉起内嵌 Web 容器并装配 Spring 上下文中的全部组件
 *
 * @author Luxon
 * @date 2026/08/26
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.liu.eka")
@MapperScan("com.liu.eka.mapper")
public class EkaApplication {

    /**
     * Spring Boot 主入口方法，启动应用并完成自动配置装配
     *
     * @param args 命令行启动参数，可透传给 Spring 容器用于覆盖默认配置项
     */
    public static void main(String[] args) {
        SpringApplication.run(EkaApplication.class, args);
    }
}
