package com.liu.eka.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.liu.eka.entity.user.EkUser;
import com.liu.eka.mapper.EkUserMapper;
import com.liu.eka.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 登录注册服务的实现说明：账号唯一性由表唯一索引与注册前查重双重保证，
 * 密码按项目当前阶段要求明文比对
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl extends ServiceImpl<EkUserMapper, EkUser> implements AuthService {

    /** 普通用户角色编码：与 ek_user.role_code 约定一致 */
    private static final int ROLE_NORMAL = 1;

    /** 账号状态：正常 */
    private static final int STATUS_NORMAL = 0;

    /**
     * 注册新账号：先查重避免重复账号，再生成 UUID 落库
     *
     * @param username 登录账号
     * @param password 登录密码
     * @return 新用户 ID
     * @throws IllegalStateException 账号已存在时抛出
     */
    @Override
    public String register(String username, String password) {
        // 步骤 1：查重，账号已被占用直接拒绝，避免依赖数据库唯一索引报错（提示不友好）
        EkUser exists = findActiveByUsername(username);
        if (exists != null) {
            throw new IllegalStateException("该账号已注册，请直接登录");
        }

        // 步骤 2：组装用户行，ID 用 UUID（与 ai_chat_session.user_id 的口径一致），角色/状态给默认值
        EkUser user = EkUser.builder()
                .id(UUID.randomUUID().toString())
                .username(username)
                .realName(username)
                .passwordHash(password)
                .roleCode(ROLE_NORMAL)
                .status(STATUS_NORMAL)
                .delFlag(0)
                .build();

        // 步骤 3：落库，失败即抛错走全局兜底
        if (!save(user)) {
            throw new IllegalStateException("注册失败，请稍后重试");
        }
        log.info("用户注册成功：username={}，userId={}", username, user.getId());
        return user.getId();
    }

    /**
     * 登录校验：按账号取有效用户，逐项比对密码与状态
     *
     * @param username 登录账号
     * @param password 登录密码
     * @return 登录成功的用户 ID
     * @throws IllegalStateException 账号不存在、密码错误或账号被停用时抛出
     */
    @Override
    public String login(String username, String password) {
        // 步骤 1：按账号取有效用户，查不到统一提示"账号或密码错误"，避免暴露账号是否存在
        EkUser user = findActiveByUsername(username);
        if (user == null) {
            throw new IllegalStateException("账号或密码错误");
        }

        // 步骤 2：密码比对，错误同样用统一提示
        if (!password.equals(user.getPasswordHash())) {
            throw new IllegalStateException("账号或密码错误");
        }

        // 步骤 3：账号停用不允许登录
        if (user.getStatus() != null && user.getStatus() != STATUS_NORMAL) {
            throw new IllegalStateException("账号已被停用，请联系管理员");
        }

        log.info("用户登录成功：username={}，userId={}", username, user.getId());
        return user.getId();
    }

    /**
     * 按用户 ID 查有效用户
     *
     * @param userId 用户 ID
     * @return 用户实体；查不到时返回 null
     */
    @Override
    public EkUser findById(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return query().eq(true, "id", userId)
                .eq(true, "del_flag", 0)
                .one();
    }

    /**
     * 按账号查未删除的用户行
     *
     * @param username 登录账号
     * @return 用户实体；查不到时返回 null
     */
    private EkUser findActiveByUsername(String username) {
        return query().eq(true, "username", username)
                .eq(true, "del_flag", 0)
                .one();
    }
}
