package com.xlc.ai.domain.agent.service;

import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.xlc.ai.domain.agent.model.valobj.intent.TaskStateVO;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 意图识别服务接口
 * 
 * 意图识别子系统的对外门面，由 {@code IntentService} 实现。能力分三组：
 *   分类：{@link #classify} —— 规则→LLM 级联识别用户意图
 *   反馈回路：{@link #reportFeedback} —— 工具执行后回报，失败可触发重分类
 *   任务态：{@link #getTaskState}/{@link #updateTaskState} —— 支撑 CONTINUE 与多步任务
 * 配置由调用方通过 {@link #configure} 注入 Agent 的 API，使意图识别复用智能体自己的模型。
 * 
 * 除了分类入口外，新增：
 * 
 *   {@link #reportFeedback} 反馈回路：下游执行后回报成功/失败，失败可触发重分类
 *   {@link #getTaskState} / {@link #updateTaskState} 任务态：支撑 CONTINUE 与多步任务
 * 
 *
 * @author xlvchao
 */
public interface IIntentService {

    /**
     * 对用户消息进行意图分类
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param message   用户消息
     * @return 意图识别结果
     */
    IntentResultVO classify(String sessionId, String userId, String message);

    /**
     * 反馈回路：下游节点执行工具后回报结果。
     * 
     * 当 success=false 且当前意图置信度不高时，IntentService 可决定重分类，
     * 返回新的意图结果；若无需重分类则返回 null。
     *
     * @param sessionId  会话 ID
     * @param lastIntent 上一次识别的意图
     * @param success    本轮工具执行是否成功
     * @param toolResult 工具执行结果文本（用于判断是否需要重分类）
     * @return 重分类后的新意图，或 null 表示维持原意图
     */
    IntentResultVO reportFeedback(String sessionId, IntentResultVO lastIntent,
                                  boolean success, String toolResult);

    /**
     * 获取当前会话的任务态
     */
    TaskStateVO getTaskState(String sessionId);

    /**
     * 获取最近一次完整意图识别结果，用于结构化 Prompt 提示；无历史时返回 null。
     */
    IntentResultVO getLastIntentResult(String sessionId);

    /**
     * 更新当前会话的任务态
     */
    void updateTaskState(String sessionId, TaskStateVO taskState);

    /**
     * 注入智能体的 API 配置，供 LLM 意图分类器复用。
     * 
     * 由调用方（如 ReActAiCallNode）在分类前传入 Agent 装配好的 OpenAiApi 和模型名，
     * 使得意图识别不再需要单独配置模型，而是使用各智能体自己的配置。
     *
     * @param openAiApi Agent 装配链路构建的 OpenAiApi
     * @param modelName Agent 配置的模型名
     */
    void configure(OpenAiApi openAiApi, String modelName);

}
