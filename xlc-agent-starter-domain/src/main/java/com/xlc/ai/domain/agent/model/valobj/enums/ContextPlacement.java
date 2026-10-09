package com.xlc.ai.domain.agent.model.valobj.enums;

/**
 * Context 在最终 Prompt 中的放置位置。
 *
 * STABLE_PREFIX 用于会话内低频变化、能帮助模型保持任务目标的上下文；
 * EPHEMERAL_SUFFIX 用于实时状态、工具反馈等高频变化内容。
 */
public enum ContextPlacement {
    STABLE_PREFIX,
    EPHEMERAL_SUFFIX
}
