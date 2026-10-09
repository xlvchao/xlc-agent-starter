package com.xlc.ai.domain.agent.model.valobj.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 意图规则定义值对象
 *
 * 每条规则描述一个意图的关键词集合与正则模式集合，
 * 由 {@code RuleIntentClassifier.RULES} 静态加载。打分公式：
 * 
 *   命中关键词   → 0.5 + (hits-1)*0.1，上限 0.7
 *   命中正则模式 → +0.2
 *   上下文加权   → +0.1（最近意图历史命中同一意图）
 *   ────────────────────────────────────
 *   置信度 ≥ 0.8 时 IntentService 直接采信，跳过 LLM 调用
 * 
 *
 * 案例（DIAGNOSE 规则）：
 * 
 *   keywords = ["挂了","502","OOM","超时", ...]
 *   patterns = ["为什么.*(?:挂|报错|失败|不通)", "排查.*问题", ...]
 *
 *   输入 "nginx 502 了为什么挂了"
 *     命中关键词 502、挂了 → 0.5 + 0.1 = 0.6
 *     命中模式 "为什么.*挂"  → +0.2  →  合计 0.8 → 直接采信
 * 
 *
 * @author xlvchao
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class IntentRuleVO {

    private IntentType intent;

    private List<String> keywords;

    private List<String> patterns;

}
