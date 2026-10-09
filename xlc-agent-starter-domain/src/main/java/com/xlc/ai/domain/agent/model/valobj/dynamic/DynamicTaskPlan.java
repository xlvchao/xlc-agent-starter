package com.xlc.ai.domain.agent.model.valobj.dynamic;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * 动态任务计划 - 一次多 Agent 派发的完整执行计划。
 * 
 * 由规划器输出 JSON 经 PlanParser 解析、PlanValidator 校验后构建，
 * 交给 DynamicAgentOrchestrator 按 DAG 依赖关系并发执行。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DynamicTaskPlan {

    /** 计划包含的任务列表，构成一个有向无环图（DAG），不允许出现依赖环 */
    private List<DynamicTask> tasks;
    /** 最大并发度，编排器通过 Semaphore 控制同时执行的子 Agent 数量 */
    @Builder.Default
    private Integer maxConcurrency = 4;
    /** 是否需要用户确认后再执行（预留字段） */
    @Builder.Default
    private Boolean requireConfirmation = false;
    /**
     * 失败即中止策略：默认 false，某个任务失败后其余无依赖任务继续执行，
     * 仅其下游任务被跳过；置 true 后，首个任务失败即不再调度新的 PENDING 任务
     * （进行中的任务会跑完），剩余任务全部置为 SKIPPED。
     */
    @Builder.Default
    private Boolean failFast = false;

}
