package com.liu.eka.controller;

import com.liu.eka.common.Result;
import com.liu.eka.common.UserContext;
import com.liu.eka.config.AuthProperties;
import com.liu.eka.entity.user.EkUser;
import com.liu.eka.entity.user.LoginUserVO;
import com.liu.eka.service.AuthService;
import com.liu.eka.util.JwtUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录注册入口：注册建号、登录签发 JWT 并写入 Cookie、登出清除 Cookie、
 * 查询当前登录用户；token 由 Cookie 承载，前端无需手动拼请求头
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    /** 登录注册服务 */
    private final AuthService authService;

    /** JWT 工具：登录成功时签发 token */
    private final JwtUtil jwtUtil;

    /** 鉴权配置：Cookie 名与有效期 */
    private final AuthProperties authProperties;

    /**
     * 注册新账号：账号唯一校验通过后落库
     *
     * @param username 登录账号，注解校验非空
     * @param password 登录密码，注解校验非空
     * @return 新用户视图
     */
    @PostMapping("/register")
    public Result<LoginUserVO> register(
            @NotBlank(message = "账号不能为空") @RequestParam String username,
            @NotBlank(message = "密码不能为空") @RequestParam String password) {
        log.info("用户注册：username={}", username);
        // 步骤 1：落库建号，拿到新用户 ID
        String userId = authService.register(username, password);
        // 步骤 2：返回可展示的用户信息
        return Result.ok(LoginUserVO.builder()
                .userId(userId)
                .username(username)
                .build());
    }

    /**
     * 登录：校验账号密码，成功后签发 JWT 写入 Cookie
     *
     * @param username 登录账号，注解校验非空
     * @param password 登录密码，注解校验非空
     * @param response 响应对象，用于写入登录 Cookie
     * @return 登录用户视图
     */
    @PostMapping("/login")
    public Result<LoginUserVO> login(
            @NotBlank(message = "账号不能为空") @RequestParam String username,
            @NotBlank(message = "密码不能为空") @RequestParam String password,
            HttpServletResponse response) {
        log.info("用户登录：username={}", username);
        // 步骤 1：校验账号密码，失败抛错走全局兜底
        String userId = authService.login(username, password);
        // 步骤 2：签发自包含 JWT 并写入 Cookie，后续请求由浏览器自动携带
        String token = jwtUtil.issue(userId);
        writeTokenCookie(response, token, authProperties.getExpireHours() * 3600);
        // 步骤 3：返回用户信息供前端展示
        EkUser user = authService.findById(userId);
        return Result.ok(LoginUserVO.builder()
                .userId(userId)
                .username(user.getUsername())
                .build());
    }

    /**
     * 登出：清空登录 Cookie（把有效期置 0 覆盖写入）
     *
     * @param response 响应对象，用于写入失效 Cookie
     * @return 恒定返回成功
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse response) {
        log.info("用户登出");
        writeTokenCookie(response, "", 0);
        return Result.ok();
    }

    /**
     * 查询当前登录用户：过滤器已把用户 ID 写入上下文，这里补全账号信息返回
     *
     * @return 当前登录用户视图；登录态失效时过滤器已拦截
     */
    @GetMapping("/me")
    public Result<LoginUserVO> me() {
        String userId = UserContext.requireUserId();
        EkUser user = authService.findById(userId);
        if (user == null) {
            throw new IllegalStateException("登录状态已失效，请重新登录");
        }
        return Result.ok(LoginUserVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .build());
    }

    /**
     * 写入登录 Cookie：前端需读取该 Cookie 判断登录态，故不设 HttpOnly；
     * SameSite 显式设为 Lax，同站跨域（前端 5173 调后端 8080）下浏览器才会带上
     *
     * @param response     响应对象
     * @param value        Cookie 值（token 或空串）
     * @param maxAgeSeconds 有效期秒数，0 表示立即失效
     */
    private void writeTokenCookie(HttpServletResponse response, String value, long maxAgeSeconds) {
        ResponseCookie cookie = ResponseCookie.from(authProperties.getCookieName(), value)
                // 路径设为根：前后端所有接口路径都能带上该 Cookie
                .path("/")
                .maxAge(maxAgeSeconds)
                // 前端 JS 需读取登录态，因此不设 HttpOnly
                .httpOnly(false)
                .secure(authProperties.isCookieSecure())
                .sameSite("Lax")
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
