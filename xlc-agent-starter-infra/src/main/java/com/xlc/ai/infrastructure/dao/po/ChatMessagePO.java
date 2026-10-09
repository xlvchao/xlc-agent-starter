package com.xlc.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话消息持久化对象（PO）
 * 
 * 对应数据库 {@code chat_message} 表，由 MyBatis 映射使用。
 * 与领域层 {@code ChatMessageEntity} 一一对应，但时间字段使用 LocalDateTime（MyBatis 友好）。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessagePO {

    /** 自增主键 */
    private Long id;

    /** 会话 ID（ADK Session.id()） */
    private String sessionId;

    /** 消息角色：user / assistant / tool / system */
    private String role;

    /** 消息内容 */
    private String content;

    /** 工具名称（仅 role=tool 时有值） */
    private String toolName;

    /** 工具调用 ID（仅 role=tool 时有值） */
    private String toolCallId;

    /** 优先级：CRITICAL / HIGH / MEDIUM / LOW */
    private String priority;

    /** 预估 token 数 */
    private Integer tokenCount;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;
}
