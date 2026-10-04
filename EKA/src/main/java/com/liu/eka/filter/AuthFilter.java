package com.liu.eka.filter;

import com.liu.eka.common.UserContext;
import com.liu.eka.config.AuthProperties;
import com.liu.eka.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 登录认证过滤器：从请求 Cookie 中取 JWT，验签通过则把用户 ID 写入
 * {@link UserContext} 供业务层使用，请求结束（含异常）时清除，避免线程复用串号
 *
 * <p>白名单路径（登录、注册、跨域预检）不校验 token，直接放行；
 * 其余路径未带有效 token 时直接返回 401，前端据此跳转登录页</p>
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    /** 免登录路径：登录、注册、登出与跨域预检请求 */
    private static final Set<String> WHITE_LIST = Set.of(
            "/auth/login", "/auth/register", "/auth/logout");

    /** JWT 工具：验签并还原用户 ID */
    private final JwtUtil jwtUtil;

    /** 鉴权配置：Cookie 名 */
    private final AuthProperties authProperties;

    /**
     * 过滤逻辑：放行预检与白名单，其余请求校验 Cookie 中的 token，
     * 校验通过写入用户上下文，失败直接回 401
     *
     * @param request    本次请求
     * @param response   本次响应
     * @param filterChain 过滤器链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 步骤 1：跨域预检请求不带业务凭证，直接放行交给 CORS 配置处理
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        // 步骤 2：白名单路径（登录/注册/登出）无需登录态，直接放行
        String path = request.getRequestURI();
        if (WHITE_LIST.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 步骤 3：从 Cookie 取 token 并验签还原用户 ID
            String userId = jwtUtil.parseUserId(readToken(request));

            // 步骤 4：token 缺失、被篡改或已过期都视为未登录，回 401 让前端跳登录页
            if (userId == null) {
                writeUnauthorized(response);
                return;
            }

            // 步骤 5：认证通过，写入当前线程的用户上下文供业务层取用
            UserContext.setUserId(userId);
            filterChain.doFilter(request, response);
        } finally {
            // 步骤 6：无论请求成功与否都清上下文，防止 Tomcat 线程复用把身份带给下一个请求
            UserContext.clear();
        }
    }

    /**
     * 从请求 Cookie 中读取登录 token
     *
     * @param request 本次请求
     * @return token 字符串；Cookie 缺失时返回 null
     */
    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        // Cookie 名由配置指定，与登录接口写入时保持一致
        String cookieName = authProperties.getCookieName();
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * 返回 401 未登录响应：响应体用统一 Result 结构，前端据 code 跳转登录页
     *
     * @param response 本次响应
     * @throws IOException 写响应体失败时抛出
     */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"登录状态已失效，请重新登录\",\"data\":null}");
    }
}
