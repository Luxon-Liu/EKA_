package com.liu.eka.service;

import com.liu.eka.entity.user.EkUser;

/**
 * 登录注册服务的接口说明：负责账号注册、登录校验与当前用户信息查询，
 * 登录成功后由调用方（控制器）负责把 JWT 写入 Cookie
 *
 * @author Luxon
 * @date 2026/09/22
 */
public interface AuthService {

    /**
     * 注册新账号：校验账号唯一后落库 ek_user 表，
     * 真实姓名默认取账号名，角色为普通用户、状态正常
     *
     * @param username 登录账号，调用方保证非空
     * @param password 登录密码，调用方保证非空
     * @return 新用户 ID
     */
    String register(String username, String password);

    /**
     * 登录校验：按账号取有效用户并比对密码
     *
     * @param username 登录账号，调用方保证非空
     * @param password 登录密码，调用方保证非空
     * @return 登录成功的用户 ID
     * @throws IllegalStateException 账号不存在、密码错误或账号被停用时抛出
     */
    String login(String username, String password);

    /**
     * 按用户 ID 查用户信息：供前端登录后拉取当前用户展示用
     *
     * @param userId 用户 ID
     * @return 用户实体；查不到时返回 null
     */
    EkUser findById(String userId);
}
