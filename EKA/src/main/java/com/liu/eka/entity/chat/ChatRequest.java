package com.liu.eka.entity.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 对话请求体：前端一次问答的入参，消息与记忆 ID 均必填
 *
 * @author Luxon
 * @date 2026/09/04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 用户本轮问题，不允许为空（由 controller 的 @Valid 拦截） */
    @NotBlank(message = "不允许为空")
    private String message;

    /** 记忆 ID：后端自增生成，新建会话接口返回，前端透传延续本会话（由 controller 的 @Valid 拦截） */
    @NotNull(message = "不允许为空")
    private Long memoryId;

    /** 本轮随消息一起发送的已上传文件 ID 列表：可为空，为空表示本轮不带文件 */
    private List<Long> fileIds;
}
