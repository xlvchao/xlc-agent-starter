package com.xlc.ai.cases;

import com.xlc.ai.core.dto.ChatRequestDTO;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * AI 智能体 ReAct 执行接口
 *
 * 核心能力：
 * - 多轮对话循环（ReAct 模式）
 * - 工具调用执行（Tool Calling）
 * - 流式输出（SSE）
 * - 步数控制与终止条件判断
 *
 * ReAct 循环流程：
 * 1. 构建用户消息，追加到历史
 * 2. 调用 AI（携带工具定义）
 * 3. 解析 AI 响应：
 *    - 包含 tool_calls → 执行工具 → 追加结果 → 回到步骤 1
 *    - 不包含 tool_calls → 返回结果给用户
 * 4. 终止条件：finish / 达到最大步数 / 异常
 *
 * @author xlvchao
 */
public interface IReActChatService {

    /**
     * 流式对话（ReAct 模式）
     *
     * @param requestDTO 对话请求
     * @return SSE 事件发射器
     */
    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

    /**
     * 普通对话（单轮，非流式）
     *
     * @param requestDTO 对话请求
     * @return 对话响应内容
     */
    String chat(ChatRequestDTO requestDTO);

}
