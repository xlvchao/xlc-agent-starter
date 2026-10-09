package com.xlc.ai.domain.agent.model.valobj.dynamic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态任务 - 多 Agent 派发计划中的任务单元。
 * 
 * 由规划器（PlannerAgentBuilder）输出的 JSON 经 PlanParser 解析而来，
 * 描述"由哪个子 Agent 执行什么请求、依赖哪些前置任务"。
 * 任务对象本身承载执行状态（status）与执行结果（result / error），
 * 在编排器（DynamicAgentOrchestrator）调度过程中被原地更新。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DynamicTask {
    /** 任务唯一标识，缺失时由 PlanParser / BatchSubAgentDispatchTool 自动生成 task-{uuid} */
    private String taskId;
    /** 要派发的子 Agent 名称，必须存在于 AgentCatalog 注册表中 */
    private String agentName;
    /** 下发给子 Agent 的完整任务指令 */
    private String request;
    /** 依赖的前置任务 ID 列表，所有依赖任务 COMPLETED 后本任务才会被调度 */
    @Builder.Default
    private List<String> dependsOn = new ArrayList<>();
    /** 单任务执行超时时间（秒），超时后任务标记为 FAILED */
    @Builder.Default
    private Integer timeoutSeconds = 120;
    /**
     * 失败后自动重试的最大次数（不含首次执行），默认 0 表示不重试。
     * 
     * 仅对"执行期失败"（超时/异常）生效；Agent 不存在等确定性错误直接 FAILED。
     * 重试间隔按 2^n 秒指数退避，上限 30 秒。注意：子 Agent 具备 SSH 写操作能力，
     * 派发非幂等写任务时应保持 0，由主 Agent 拿到失败原因后自行决策。
     */
    @Builder.Default
    private Integer maxRetries = 0;
    /** 实际执行尝试次数（含首次），由 SubAgentDispatchService 回写，供主 Agent 观察重试情况 */
    @Builder.Default
    private Integer attempts = 0;
    /** 任务当前状态，随编排调度流转 */
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;
    /** 子 Agent 执行完成后的最终回复文本 */
    @Builder.Default
    private String result = "";
    /** 执行失败时的错误信息 */
    @Builder.Default
    private String error = "";
}
