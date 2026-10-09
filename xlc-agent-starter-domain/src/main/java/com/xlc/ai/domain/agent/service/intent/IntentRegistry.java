package com.xlc.ai.domain.agent.service.intent;

import com.xlc.ai.domain.agent.model.valobj.intent.IntentRuleVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentType;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图类型注册表
 *
 * 启动时把配置文件中的意图加载为全局唯一的 {@link IntentType} 实例并缓存，
 *  每次返回同一实例，因此 {@code ==} 比较依然成立，
 * 兼容原枚举的引用语义。
 *
 * @author xlvchao
 */
@Component
public class IntentRegistry {
    private final Map<String, IntentType> intentTable = new LinkedHashMap<>();
    private final List<IntentRuleVO> intentRules = new ArrayList<>();
    private String llmIntentClassifyPrompt;

    private final IntentType unknown = new IntentType("UNKNOWN", "未知");
    private final IntentType compound = new IntentType("COMPOUND", "复合指令");
    private final IntentType continueIntent = new IntentType("CONTINUE", "继续");
    private final IntentType chat = new IntentType("CHAT", "闲聊");


    public IntentType getUnknown() {
        return unknown;
    }

    public IntentType getCompound() {
        return compound;
    }

    public IntentType getContinue() {
        return continueIntent;
    }

    public IntentType getChat() {
        return chat;
    }

    public IntentRegistry(AgentConfigure agentConfigure) {
        llmIntentClassifyPrompt = agentConfigure.getModule().getLlmIntentClassifyPrompt();
        for (AgentConfigure.Module.Intent it : agentConfigure.getModule().getCommonIntents()) {
            if (it.getValue() == null) {
                continue;
            }
            String value = it.getValue().trim().toUpperCase();
            IntentType intentType = new IntentType(value, it.getLabel());

            intentTable.put(value, intentType);
            intentRules.add(IntentRuleVO.builder()
                    .intent(intentType)
                    .keywords(it.getKeywords())
                    .patterns(it.getPatterns() == null ? List.of() : it.getPatterns())
                    .build());
        }
    }

    public IntentType getIntentType(String intent) {
        return intentTable.getOrDefault(intent.toUpperCase(), null);
    }

    public List<IntentRuleVO> getIntentRules() {
        return intentRules;
    }

    public String getLlmIntentClassifyPrompt() {
        return llmIntentClassifyPrompt;
    }
}
