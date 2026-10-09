package com.xlc.ai.domain.agent.service.context.reducer;

import java.util.List;
import java.util.Map;

/**
 * 消息裁剪器接口
 * 
 * 功能：在 token 预算内对消息历史做裁剪，
 * 避免多轮对话 + 工具结果把 LLM 上下文窗口撑爆。
 * 
 * 体系架构：
 * 
 *                 ChatContextService.trimHistory()
 *                          |
 *                          v
 *                    HybridReducer（组合策略，实际使用）
 *                     |              |
 *                     v              v
 *            PriorityReducer   SlidingWindowReducer
 *            （重要性：错误/     （时效性：最近20条
 *              关键指令不丢）     + token 预算）
 *                     |              |
 *                     +------∩-------+
 *                            |
 *                            v
 *                  交集 + 保底最近2条 --> 裁剪后的历史
 * 
 *
 * @author xlvchao
 */
public interface MessageReducer {

    /**
     * 裁剪消息历史
     *
     * @param messages    原始消息列表（按时间正序）
     * @param tokenBudget token 预算
     * @return 裁剪后的消息列表（保持时间正序）
     */
    List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget);

}
