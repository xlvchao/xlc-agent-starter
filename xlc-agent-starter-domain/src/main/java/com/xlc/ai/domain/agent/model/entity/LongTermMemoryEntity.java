package com.xlc.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 长期记忆实体（领域层）
 * 
 * 对应数据库 {@code long_term_memory} 表，存储从对话流中自动提取的结构化记忆。
 * 由 {@code LongTermMemoryService} 在 ReAct 循环各阶段自动提取并写入。
 * 
 * 四种记忆类型（{@code memoryType}）：
 * 
 *   {@code USER_PREFERENCE} - 用户偏好（含"以后""默认""记住"等偏好词），key=sha1("pref:"+内容)
 *   {@code ENVIRONMENT_FACT} - 环境事实（OS 检测），key="os:ubuntu"
 *   {@code TROUBLESHOOTING_CASE} - 排查案例（失败信号/助手结论），key=sha1("failure:"/"assistant:"+内容)
 * 
 * 
 * 唯一键 (userId, memoryType, memoryKey) 保证同一记忆不重复入库，重复提取时 hit_count+1。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTermMemoryEntity {

    /** 自增主键 */
    private Long id;

    /** 用户 ID */
    private String userId;

    /** 来源会话 ID */
    private String sessionId;

    /** 记忆类型：USER_PREFERENCE / ENVIRONMENT_FACT / TROUBLESHOOTING_CASE */
    private String memoryType;

    /** 记忆去重键（不同类型有不同生成策略，保证幂等） */
    private String memoryKey;

    /** 记忆内容（已截断，最长 300~500 字符） */
    private String content;

    /** 关键词集合（逗号分隔，用于召回时关键词重叠匹配） */
    private String keywords;

    /** 来源角色：user / tool / assistant */
    private String sourceRole;

    /** 置信度（0.0~1.0，USER_PREFERENCE=0.85 最高） */
    private Double confidence;

    /** 命中/更新次数（同一记忆被重复提取时累加） */
    private Integer hitCount;

    /** 创建时间 */
    private Date createdAt;

    /** 更新时间 */
    private Date updatedAt;
}
