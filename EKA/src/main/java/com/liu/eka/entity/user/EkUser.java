package com.liu.eka.entity.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体（对应 ek_user 表）：承载登录账号与角色信息，
 * 用户 ID 为 varchar(36)，与 ai_chat_session.user_id 的归属口径保持一致
 *
 * @author Luxon
 * @date 2026/09/22
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@TableName("ek_user")
public class EkUser {

    /** 用户 ID（varchar36，新增时由后端生成 UUID） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 登录账号，全局唯一 */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** 密码：按项目当前阶段的要求明文存放 */
    private String passwordHash;

    /** 角色编码：1 普通用户，其余值预留扩展 */
    private Integer roleCode;

    /** 账号状态：0 正常 1 停用 */
    private Integer status;

    /** 删除标记：0 有效 1 删除 */
    private Integer delFlag;

    /** 创建时间（数据库自动维护） */
    private LocalDateTime createdAt;

    /** 更新时间（数据库自动维护） */
    private LocalDateTime updatedAt;
}
