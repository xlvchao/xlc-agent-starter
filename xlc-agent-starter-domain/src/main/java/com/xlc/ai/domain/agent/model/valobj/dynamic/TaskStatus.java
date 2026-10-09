package com.xlc.ai.domain.agent.model.valobj.dynamic;

/**
 * 任务执行状态机：PENDING → RUNNING → COMPLETED / FAILED；
 * PENDING → SKIPPED（依赖的上游任务 FAILED/SKIPPED，或计划因 failFast 中止）。
 * 
 * 由 DynamicAgentOrchestrator 在调度循环中依据该状态筛选可执行任务，
 * 子 Agent 执行完成后由 SubAgentDispatchService 更新终态。
 */
public enum TaskStatus {
    /** 已入计划、尚未开始执行（等待依赖任务完成且并发额度可用） */
    PENDING,
    /** 正在派发给子 Agent 执行 */
    RUNNING,
    /** 执行成功，result 中包含子 Agent 回复 */
    COMPLETED,
    /** 执行失败（找不到 Agent / 超时 / 重试耗尽），error 中包含原因 */
    FAILED,
    /** 未执行即被跳过：依赖的上游任务失败，或计划开启 failFast 后其余任务被中止 */
    SKIPPED
}
