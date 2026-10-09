package com.xlc.ai.domain.agent.model.valobj.intent;

import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 意图类型值对象（替代原 {@code IntentTypeEnumVO} 枚举）
 *
 * <p>由配置文件 {@code ai.agent.intent} 动态加载，通过 {@code IntentTypeRegistry}
 * 维护全局唯一实例，因此不同地方拿到同一 {@code value} 时是同一个对象，可直接用 {@code ==} 比较。
 *
 * @author xlvchao
 */
@Getter
@EqualsAndHashCode(of = "value")
public class IntentType {

    /**
     * 意图稳定标识（如 "DIAGNOSE"），用于 DB 存储、LLM 输出、日志与代码逻辑判定
     */
    private final String value;

    /**
     * 意图中文名（如 "诊断问题"），用于 Prompt 注入与前端展示
     */
    private final String label;

    public IntentType(String value, String label) {
        this.value = value;
        this.label = label;
    }

    /**
     * 兼容原枚举 {@code name()} 语义，返回 {@link #value}。
     */
    public String name() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }


}
