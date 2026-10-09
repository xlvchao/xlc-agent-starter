package com.xlc.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 对话消息实体（领域层）
 * 
 * 对应数据库 {@code chat_message} 表，记录 ReAct 循环中每一条消息（user/assistant/tool/system）。
 * 由 {@code ReActAiCallNode}（user/assistant）和 {@code ReActToolCallNode}（tool）在消息流转时落库。
 * 
 * 关键字段说明：
 * 
 *   {@code role} - 复用 OpenAI 消息角色规范：user / assistant / tool / system
 *   {@code priority} - 与 HybridReducer 对齐，HIGH 优先级消息在裁剪时优先保留
 *   {@code tokenCount} - 预估 token 数（≈ content.length() / 2），用于上下文裁剪预算
 * 
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageEntity {

    /** 自增主键 */
    private Long id;

    /** 会话 ID（ADK Session.id()） */
    private String sessionId;

    /** 消息角色：user / assistant / tool / system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 工具名称（仅 role=tool 时有值，如 "executeCommand"） */
    private String toolName;

    /** 工具调用 ID（仅 role=tool 时有值，用于关联 assistant 的 tool_call） */
    private String toolCallId;

    /** 优先级：CRITICAL / HIGH / MEDIUM / LOW，工具结果默认 HIGH */
    private String priority;

    /** 预估 token 数（≈ content.length() / 2） */
    private Integer tokenCount;

    /** 创建时间 */
    private Date createdAt;
}
