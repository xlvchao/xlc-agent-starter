package com.xlc.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 会话元数据实体（领域层）
 * 
 * 对应数据库 {@code chat_session} 表，记录一次对话会话的元信息。
 * 由 {@code ChatService.createSession()} 在创建 ADK Session 时同步落库。
 * 
 * {@code id} 即 ADK {@code Session.id()}（UUID），{@code messageCount} 在每次保存消息时 +1，
 * 方便前端列表展示活跃度。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionEntity {

    /** 会话 ID（ADK Session.id()，UUID） */
    private String id;

    /** 智能体 ID */
    private String agentId;

    /** 用户 ID */
    private String userId;

    /** 会话标题（创建时默认"新会话"） */
    private String title;

    /** 消息数量（每次保存消息时 +1） */
    private Integer messageCount;

    /** 创建时间 */
    private Date createdAt;

    /** 更新时间 */
    private Date updatedAt;
}
