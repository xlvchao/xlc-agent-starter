package com.xlc.ai.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedList;
import java.util.List;

/**
 * 任务态（用于支撑 CONTINUE 与多步运维任务的自纠错）
 * 
 * 当意图识别命中 COMPOUND 或多步任务时，记录当前任务与其子步骤，
 * 使得 "继续" 这类指令可以精确续到下一步，而不是盲猜上一个意图。
 *
 * 生命周期与流转：
 * 
 *   COMPOUND 命中 ──→ 主模型拆解出子步骤 ──→ 写入 TaskStateVO(steps, currentStepIndex=0)
 *        ↓ 每步执行
 *   反馈成功   → currentStepIndex++（推进）
 *   反馈失败   → lastFeedbackFailed=true（触发重分类）
 *   全部完成   → completed=true
 *        ↓
 *   用户输入 "继续" → CONTINUE 命中 → 读取 currentStepIndex 续接下一步
 * 
 *
 * 案例（"看下 502 是不是改配置导致"）：
 * 
 *   rootIntent=COMPOUND
 *   steps=["查 nginx 错误日志","查 redis 连接池配置","对比最近配置变更"]
 *   currentStepIndex: 0 → 1（第一步完成）→ 用户说"继续"→ 续到 step 1
 * 
 *
 * @author xlvchao
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskStateVO {

    /** 当前任务描述（由主模型或意图层归纳） */
    private String taskDescription;

    /** 任务对应的根意图（如 COMPOUND / DEPLOY / DIAGNOSE） */
    private IntentType rootIntent;

    /** 子步骤列表（按执行顺序） */
    @Builder.Default
    private List<String> steps = new LinkedList<>();

    /** 当前执行到的步骤下标（从 0 开始，-1 表示尚未开始） */
    @Builder.Default
    private int currentStepIndex = -1;

    /** 任务是否已完成 */
    @Builder.Default
    private boolean completed = false;

    /** 最近一次反馈是否失败（用于触发重分类） */
    @Builder.Default
    private boolean lastFeedbackFailed = false;

}
