package com.xlc.ai.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 意图历史条目
 *
 * 由 {@code ContextTracker.updateContext} 在每次分类后追加到
 * {@code ConversationContextVO.recentIntents}（滑动窗口 10 条），
 * 供规则分类器做上下文加权（命中同意图 +0.1），并供 LLM 分类器在 Prompt
 * 中展示"最近意图"以提升连贯性。
 *
 * 案例：
 * 
 *   第1轮 DIAGNOSE(conf=0.9) → 入窗
 *   第2轮 DIAGNOSE(conf=0.85)→ 入窗；规则层因历史含 DIAGNOSE → +0.1 加权
 *   第11轮 → 窗口满，第1轮条目被淘汰
 * 
 *
 * @author xlvchao
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentHistoryEntryVO {

    private IntentType intent;

    private double confidence;

    private long timestamp;

}
