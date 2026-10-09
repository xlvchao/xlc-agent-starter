package com.xlc.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话里程碑持久化对象（PO）
 * 
 * 对应数据库 {@code chat_milestone} 表，记录任务切换、错误、用户纠偏等关键事件。
 * 由 {@code MilestoneTracker} 检测到关键事件后写入。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMilestonePO {

    /** 自增主键 */
    private Long id;

    /** 会话 ID */
    private String sessionId;

    /** 里程碑类型：TASK_CHANGE / TASK_COMPLETE / USER_CORRECTION / ERROR 等（对应 MilestoneVO.Type 枚举） */
    private String type;

    /** 内容摘要 */
    private String content;

    /** 创建时间（DB 默认 CURRENT_TIMESTAMP） */
    private LocalDateTime createdAt;
}
