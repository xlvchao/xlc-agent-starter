package com.xlc.ai.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedList;

/**
 * 会话级对话上下文（意图历史 + 任务态）
 *
 * 由 {@code ContextTracker} 按 sessionId 维护，生命周期内贯穿一次会话的所有轮次。
 * 既是分类器的<strong>上下文加权输入</strong>，也是反馈回路与 CONTINUE 的<strong>状态载体</strong>。
 *
 * 流转关系：
 * 
 *   ContextTracker.getContext(sessionId)
 *        ↓ 提供
 *   ConversationContextVO ─┬─ recentIntents      → 规则/LLM 加权 +0.1
 *                          ├─ lastIntent          → 上下文连贯性参考
 *                          ├─ taskState           → CONTINUE 续接依据
 *                          └─ consecutiveFailures → ≥2 跳过规则层、强制 LLM 兜底
 *        ↑ 回写
 *   ContextTracker.updateContext / recordFeedback / setTaskState
 * 
 *
 * 案例：
 * 
 *   连续两轮识别为 DIAGNOSE，第三轮 "再看看" 
 *     → recentIntents 含 DIAGNOSE → 规则加权 +0.1，倾向续 DIAGNOSE
 *   工具连续失败 2 次 → consecutiveFailures=2 → 跳规则层，全交主模型
 * 
 *
 * @author xlvchao
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ConversationContextVO {

    /** 最近 N 条意图历史（滑动窗口） */
    private LinkedList<IntentHistoryEntryVO> recentIntents;

    /** 对话轮次 */
    private int turnCount;

    /** 会话开始时间 */
    private long sessionStartTime;

    /** 最近一次活跃时间（用于过期淘汰） */
    private long lastActiveTime;

    /** 上一次识别到的意图 */
    private IntentType lastIntent;

    /** 上一次完整识别结果，供 Prompt 渲染置信度、候选与实体 */
    private IntentResultVO lastIntentResult;

    /** 当前任务态（支撑 CONTINUE 与多步任务自纠错） */
    private TaskStateVO taskState;

    /** 连续意图失败次数（达到阈值后强制全交主模型） */
    @Builder.Default
    private int consecutiveFailures = 0;
}
