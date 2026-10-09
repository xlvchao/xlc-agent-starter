package com.xlc.ai.domain.agent.service.context.provider.impl;

import com.xlc.ai.domain.agent.model.valobj.intent.TaskStateVO;
import com.xlc.ai.domain.agent.service.IIntentService;
import com.xlc.ai.domain.agent.model.valobj.enums.ContextPlacement;
import com.xlc.ai.domain.agent.service.context.provider.ContextProvider;
import com.xlc.ai.domain.agent.service.prompt.PromptEnvelope;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务上下文提供者（order=20）
 * 
 * 功能：从消息历史中提取首条 user 消息作为"当前任务描述"，
 * 让模型在长对话、多轮工具调用后仍能记住最初的目标（防"任务漂移" - 面试考点）。
 * 
 * 运行过程：
 * 
 *   messageHistory（时间正序）
 *   +----------------------------------------------------+
 *   | [0] user:      "帮我排查 nginx 502"   <--+ 初始目标 |
 *   | [1] assistant: "好的，先看日志..."         |          |
 *   | [2] tool:      "tail -100 error.log..."  |          |
 *   | [3] assistant: "发现 upstream 超时..."   |          |
 *   | ...（几十轮后，模型容易忘记最初任务）       |          |
 *   +----------------------------------------------------+
 *                     |
 *                     v  TaskProvider.provide()
 *            优先从 originalUserTask 获取，否则从前往后找第一条 role=user
 *                     |
 *                     v
 *        Map{ taskDescription: "帮我排查 nginx 502" }
 *                     |
 *                     v
 *   DynamicPromptBuilder 渲染为消息前缀的 [当前任务] 段落
 *   --> 每轮对话都提醒模型"你最初的任务是什么"
 * 
 * 设计说明：取"首条"而非"最近"——首条用户消息代表会话的初始目标；
 * 后续 user 消息多为补充/纠偏，已由 MilestoneProvider 覆盖。
 * 为了防止多轮对话中历史被裁剪或者被动态前缀污染，
 * 在 ReActRootNode 初始化时已经将原始用户任务提取并存入 DynamicContext（可扩展从缓存中读取稳定任务源）。
 *
 * @author xlvchao
 */
@Component
public class TaskProvider implements ContextProvider {

    @Resource
    private IIntentService intentService;

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public ContextPlacement getPlacement() {
        return ContextPlacement.STABLE_PREFIX;
    }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        // 优先从 TaskStateVO 获取：TaskStateVO 的任务描述是经过意图分类系统"验证"过的，
        // 比从消息历史中推断更准确。分类时如果识别为业务意图，就把当前消息设为任务描述。
        TaskStateVO taskState = intentService.getTaskState(sessionId);
        if (taskState != null && taskState.getTaskDescription() != null && !taskState.getTaskDescription().isBlank()) {
            result.put("taskDescription", taskState.getTaskDescription());
            return result;
        }

        // 降级：从消息历史中找第一条 user 消息，并清洗可能带的后缀污染。
        // 这里我们先对可能带后缀的 user message 做一次简单清洗。

        if (messageHistory != null) {
            messageHistory.stream()
                    .filter(m -> "user".equals(m.get("role")))
                    .findFirst()
                    .ifPresent(m -> {
                        String content = (String) m.get("content");
                        if (content != null) {
                            result.put("taskDescription", PromptEnvelope.extractUserMessage(content));
                        }
                    });
        }

        return result;
    }

}
