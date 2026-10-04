package com.liu.eka.util;

import com.liu.eka.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：负责登录 token 的签发与校验。token 自包含用户标识与过期时间，
 * 服务端无需存储，验签通过且未过期即视为登录有效
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Component
@RequiredArgsConstructor
public class JwtUtil {

    /** 用户标识在 JWT 载荷中的声明名 */
    private static final String CLAIM_USER_ID = "userId";

    /** 鉴权配置：密钥与有效期 */
    private final AuthProperties authProperties;

    /**
     * 签发登录 token：载荷写入用户 ID，并按配置的有效期设置过期时间
     *
     * @param userId 登录用户 ID，不允许为空
     * @return 签名后的 JWT 字符串
     */
    public String issue(String userId) {
        // 步骤 1：按当前时间与有效小时数算出过期时刻
        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        Date expiration = new Date(now + authProperties.getExpireHours() * 3600_000L);

        // 步骤 2：HS256 签名签发：主体放用户 ID，另加自定义声明便于取值
        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_USER_ID, userId)
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(secretKey())
                .compact();
    }

    /**
     * 校验并解析 token：验签与过期判断都在此完成
     *
     * @param token 前端带来的 JWT 字符串，可为空
     * @return 解析出的用户 ID；token 为空、签名不合法或已过期时返回 null
     */
    public String parseUserId(String token) {
        // 步骤 1：空 token 直接判为未登录，避免后续解析抛异常
        if (token == null || token.isBlank()) {
            return null;
        }

        try {
            // 步骤 2：验签解析载荷，签名错误或已过期都会在此抛异常（过期由 jjwt 内置校验）
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // 步骤 3：优先取自定义声明，缺失时回退到主体
            String userId = claims.get(CLAIM_USER_ID, String.class);
            return userId != null && !userId.isBlank() ? userId : claims.getSubject();
        } catch (Exception e) {
            // 步骤 4：任何解析失败（过期/篡改/格式错）统一按未登录处理
            return null;
        }
    }

    /**
     * 由配置密钥构造 HMAC-SHA256 密钥对象
     *
     * @return 签名/验签用的密钥
     */
    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(authProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }
}
