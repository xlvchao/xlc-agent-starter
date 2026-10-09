package com.xlc.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话会话持久化对象（PO）
 * 
 * 对应数据库 {@code chat_session} 表，记录一次对话会话的元信息。
 * 由 {@code ChatService.createSession()} 在创建 ADK Session 时同步插入。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionPO {

    /** 会话 ID（ADK Session.id()，UUID，主键） */
    private String id;

    /** 智能体 ID */
    private String agentId;

    /** 用户 ID */
    private String userId;

    /** 会话标题 */
    private String title;

    /** 消息数量（每次保存消息时 +1） */
    private Integer messageCount;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;

    /** 更新时间（DB 默认 ON UPDATE CURRENT_TIMESTAMP） */
    private LocalDateTime updatedAt;
}
