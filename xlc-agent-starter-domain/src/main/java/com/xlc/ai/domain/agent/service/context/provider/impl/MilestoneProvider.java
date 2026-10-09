package com.xlc.ai.domain.agent.service.context.provider.impl;

import com.xlc.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.xlc.ai.domain.agent.service.context.provider.ContextProvider;
import com.xlc.ai.domain.agent.service.prompt.dynamic.MilestoneTracker;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 里程碑上下文提供者（order=30）
 * 
 * 功能：把会话中的"关键事件"（用户纠偏、任务切换、工具报错等）注入上下文，
 * 让模型感知对话过程中的重要转折点，避免重复犯错。
 * 
 * 运行过程：
 * 
 *   ReAct 循环中（ReActAiCallNode / ReActToolCallNode）
 *   每条 user/tool 消息 --> PromptService.detectAndRecordMilestone()
 *                              |
 *                              v
 *                     MilestoneTracker（正则识别 + 会话级缓存）
 *                     user: "不对/换个思路" --> TASK_CHANGE
 *                     user: "完成/搞定"     --> TASK_COMPLETE
 *                     user: "不要/停/别"    --> USER_CORRECTION
 *                     tool: "error/failed"  --> ERROR
 *                              |
 *   ============ 下一轮 provide() 时 ============
 *                              |
 *                              v
 *                     MilestoneTracker.getRecent(sessionId, 10)
 *                              |
 *                              v
 *                     Map{ milestoneVOS: 最近10条里程碑 }
 *                              |
 *                              v
 *              消息前缀 [关键事件] 段落：- [ERROR] xxx
 * 
 *
 * @author xlvchao
 */
@Component
public class MilestoneProvider implements ContextProvider {

    @Resource
    private MilestoneTracker milestoneTracker;

    @Override
    public String getName() {
        return "milestoneVO";
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<MilestoneVO> milestoneVOS = milestoneTracker.getRecent(sessionId, 10);
        result.put("milestoneVOS", milestoneVOS);
        return result;
    }

}
