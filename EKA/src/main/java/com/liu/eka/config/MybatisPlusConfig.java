package com.liu.eka.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置：注册分页拦截器，让 Service 链式 query().page(...) 真正生效
 *
 * @author Luxon
 * @date 2026/09/20
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册分页内部拦截器：MyBatis-Plus 的分页能力由插件提供，
     * 未注册时 page(...) 会静默退化为普通查询——不拼 LIMIT、不执行 COUNT，返回全表且 total 恒为 0
     *
     * @return 已装配分页拦截器的 MyBatis-Plus 插件
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 步骤 1：创建插件容器，后续内部拦截器都挂到它身上
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 步骤 2：装配 MySQL 方言的分页拦截器，由它自动改写 SQL 追加 LIMIT 与 COUNT
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));

        return interceptor;
    }
}
