package com.liu.eka.common;

/**
 * 登录用户上下文：持有当前请求的登录用户 ID，由认证过滤器在请求进入时写入、
 * 请求结束时清除；业务层通过 {@link #getUserId()} 取得当前用户，
 * 用于会话/文件的归属过滤与鉴权
 *
 * <p>底层用 ThreadLocal 存放，保证同一请求线程内可见、请求之间互不串扰；
 * 过滤器必须在 finally 中调用 {@link #clear()}，否则线程复用（Tomcat 线程池）
 * 会把上一个请求的用户身份带到下一个请求</p>
 *
 * @author Luxon
 * @date 2026/09/22
 */
public final class UserContext {

    /** 当前登录用户 ID：varchar(36)，对应 ek_user.id */
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    /**
     * 工具类不允许实例化
     */
    private UserContext() {
    }

    /**
     * 写入当前请求的登录用户 ID
     *
     * @param userId 登录用户 ID，不允许为空
     */
    public static void setUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    /**
     * 取当前请求的登录用户 ID
     *
     * @return 登录用户 ID；未登录（过滤器未写入）时返回 null
     */
    public static String getUserId() {
        return CURRENT_USER_ID.get();
    }

    /**
     * 取当前请求的登录用户 ID，未登录直接抛错
     *
     * <p>供业务层使用：进入此处即表示已通过认证过滤器的放行判断，
     * 取不到说明是漏配白名单等配置问题，属于服务端异常而非用户输入问题</p>
     *
     * @return 登录用户 ID，非空
     * @throws IllegalStateException 当前请求未登录时抛出
     */
    public static String requireUserId() {
        String userId = CURRENT_USER_ID.get();
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("登录状态已失效，请重新登录");
        }
        return userId;
    }

    /**
     * 清除当前线程的用户上下文：过滤器在 finally 中调用，防止线程复用串号
     */
    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
