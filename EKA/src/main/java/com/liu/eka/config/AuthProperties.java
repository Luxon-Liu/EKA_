package com.liu.eka.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录鉴权配置：承载 JWT 密钥与有效期，由 application.yml 的 auth 段绑定
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /** JWT 签名密钥：HS256 要求密钥不短于 32 字节，生产环境必须替换为随机长串 */
    private String secret;

    /** token 有效期（小时）：签发时写入 exp，过期后过滤器验签失败按未登录处理 */
    private long expireHours;

    /** 登录 token 写入的 Cookie 名：前后端约定同名，过滤器按此名读取 */
    private String cookieName;

    /** 是否给 Cookie 加 Secure 标记：仅 HTTPS 下可携带，本地 HTTP 开发需为 false */
    private boolean cookieSecure;
}
