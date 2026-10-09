package com.xlc.ai.domain.agent.model.valobj.prompt;

import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.List;

@Data
@Builder
public class PromptContextVO {

    /**
     * 操作系统信息
     */
    private String osInfo;

    /**
     * 最近执行的任务（命令）
     */
    private List<String> recentCommands;

    /**
     * 里程碑记录
     */
    private List<MilestoneVO> milestoneVOS;

    /**
     * 工具执行摘要
     */
    private String toolResultSummary;

    /**
     * 长期记忆摘要（跨会话、结构化召回）
     */
    private String longTermMemorySummary;

    /**
     * 当前任务描述（首条用户消息）
     */
    private String taskDescription;

    /**
     * 当前意图标签（由意图识别系统经 PromptService.buildEnrichedMessage(intentLabel) 注入）。
     * 
     * 值为 {@code IntentType.getValue()}（如 "DIAGNOSE"），可为 null 表示未识别。
     * 由 {@code DynamicPromptBuilder} 渲染为消息前缀 "[用户意图] xxx"，让主模型感知意图但不强制路由。
     */
    private String intentLabel;

    /**
     * 结构化意图识别结果；优先于 intentLabel 使用，能保留置信度、候选与实体。
     */
    private IntentResultVO intentResult;

    /**
     * 声明为 STABLE_PREFIX (稳定) 的 Provider 输出。
     */
    @Builder.Default
    private Map<String, Object> stableContext = Map.of();

    /**
     * 声明为 EPHEMERAL_SUFFIX (临时)  的 Provider 输出。
     */
    @Builder.Default
    private Map<String, Object> ephemeralContext = Map.of();
}
