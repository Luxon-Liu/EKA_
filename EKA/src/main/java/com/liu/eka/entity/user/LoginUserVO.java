package com.liu.eka.entity.user;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录用户视图：登录/注册成功后返回给前端展示的字段，
 * 不含密码等敏感信息
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LoginUserVO {

    /** 用户 ID */
    private String userId;

    /** 登录账号 */
    private String username;
}
