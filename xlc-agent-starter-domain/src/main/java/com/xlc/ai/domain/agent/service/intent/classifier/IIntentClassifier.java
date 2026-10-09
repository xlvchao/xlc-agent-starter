package com.xlc.ai.domain.agent.service.intent.classifier;

import com.xlc.ai.domain.agent.model.valobj.intent.ConversationContextVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentType;
import com.xlc.ai.domain.agent.service.intent.IntentService;
import com.xlc.ai.domain.agent.service.intent.classifier.impl.LLMIntentClassifier;
import com.xlc.ai.domain.agent.service.intent.classifier.impl.RuleIntentClassifier;

/**
 * 意图分类器接口
 *
 * 意图识别的统一抽象，有两个实现构成级联：
 * 
 *   {@link RuleIntentClassifier}（第1层，1ms，关键词+正则）注意，也有一些通用智能体不设计内部的意图识别，注意依赖 LLM 调用处理，但可能每次响应都比较慢。这个部分就类似于是查本地缓存/redis还是查库一样
 *   {@link LLMIntentClassifier}（第2层，100~500ms，LLM 兜底）
 * 
 * 调度由 {@code IntentService} 完成：规则置信度不足时才下沉到 LLM。
 *
 * context 参数携带最近意图历史，分类器可据此加权，使连续多轮同类意图更易命中。
 *
 * @author xlvchao
 */
public interface IIntentClassifier {

    /**
     * 对消息进行意图分类。
     * 
     * 实现类应基于关键词/规则（规则分类器）或 LLM 语义理解（LLM 分类器）
     * 将用户消息映射到 {@link IntentType}，并附带置信度和候选意图。
     * 
     * 上下文使用：{@code context} 携带最近意图历史与任务态，分类器可据此
     * 进行加权（如连续多轮同类意图更容易命中），而非孤立地对单条消息打分。
     * 
     * 兜底约定：无论内部分类逻辑如何，当无法判断时应返回
     * {@code IntentTypeRegistry.getUnknown()}（confidence=0），由上层 {@link IntentService}
     * 统一决策是否交给主模型处理。
     *
     * @param message 用户原始消息
     * @param context 会话上下文（最近意图历史、任务态等），可为 null
     * @return 意图识别结果，不得返回 null
     */
    IntentResultVO classify(String message, ConversationContextVO context);

}
