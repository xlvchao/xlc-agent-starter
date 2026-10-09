package com.xlc.ai.core.dto;

import lombok.Data;

/**
 * ReAct 对话事件 DTO
 *
 * @author xlvchao
 */
@Data
public class ReActEventDTO {

    /**
     * 事件类型
     * - text: 文本片断
     * - tool_call: 工具调用开始
     * - tool_result: 工具执行结果
     * - round_end: 一轮结束
     * - done: 全部完成
     * - error: 错误
     */
    private String event;

    /**
     * 事件内容（文本、片断 ID 等）
     */
    private String content;

    /**
     * 工具调用 ID（tool_call / tool_result 时）
     */
    private String toolCallId;

    /**
     * 工具名称（tool_call 时）
     */
    private String toolName;

    /**
     * 工具调用状态（tool_call / tool_result 时）
     * - pending: 等待执行
     * - running: 执行中
     * - success: 执行成功
     * - error: 执行失败
     */
    private String status;

    /**
     * 完整文本（累积，event=text 时）
     */
    private String fullText;

    /**
     * 步数信息（round_end 时）
     */
    private StepInfo stepInfo;

    @Data
    public static class StepInfo {
        /** 当前步数 */
        private int currentStep;
        /** 最大步数 */
        private int maxSteps;
        /** 是否继续执行 */
        private boolean shouldContinue;
        /** 工具调用总数 */
        private int totalToolCalls;
    }

}
