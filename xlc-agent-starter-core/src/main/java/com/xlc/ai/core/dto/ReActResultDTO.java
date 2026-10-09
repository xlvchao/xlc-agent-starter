package com.xlc.ai.core.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * ReAct 执行结果 DTO
 *
 * @author xlvchao
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReActResultDTO {

    /**
     * 最终响应内容
     */
    private String finalResponse;

    /**
     * 总执行步数
     */
    private int totalSteps;

    /**
     * 总工具调用次数
     */
    private int totalToolCalls;

    /**
     * 是否达到最大步数终止
     */
    private boolean maxStepsReached;

    /**
     * 是否用户手动停止
     */
    private boolean userStopped;

    /**
     * 是否 idle 超时终止
     */
    private boolean idleTimeout;

    /**
     * 终止原因：completed / finish / max_steps / max_tool_calls / user_stop / idle_timeout / error
     */
    private String stopReason;

    /**
     * 工具调用列表
     */
    private List<Map<String, Object>> toolCalls;

    /**
     * 工具执行结果列表
     */
    private List<Map<String, Object>> toolResults;

    /**
     * 错误信息（如有）
     */
    private String error;

}
