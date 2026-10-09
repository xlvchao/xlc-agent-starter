package com.xlc.ai.domain.agent.service.prompt.dynamic;

import com.xlc.ai.domain.agent.model.valobj.prompt.MilestoneVO;
import com.xlc.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentResultVO;
import com.xlc.ai.domain.agent.model.valobj.intent.IntentType;
import com.xlc.ai.domain.agent.service.intent.IntentRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 动态 Prompt 构建器
 * 
 * 负责将 {@link PromptContextVO} 中的环境信息、最近命令、里程碑事件
 * 翻译成模型可读的结构化文本，提供两种构建方式：
 * 
 *   {@link #build} —— 追加到 system instruction 末尾
 *   {@link #buildStableContext}/{@link #buildEphemeralContext} —— 当前主链路使用，
 *   分别渲染稳定前缀和动态尾部
 * 
 *
 * @author xlvchao
 */
@Slf4j
@Component
public class DynamicPromptBuilder {

    /** 意图类型注册表——用于判断 UNKNOWN 等特殊意图 */
    @jakarta.annotation.Resource
    private IntentRegistry intentRegistry;


    /**
     * 将动态上下文追加到基础指令后面，拼成完整的 system instruction。
     * 
     * 适用于运行期可修改 system instruction 的场景。
     *
     * @param baseInstruction 基础系统指令文本
     * @param ctx             动态上下文，为 null 时直接返回基础指令
     * @return 拼接了环境信息、最近命令、里程碑事件的完整指令
     */
    public String build(String baseInstruction, PromptContextVO ctx) {
        if (ctx == null) {
            return baseInstruction;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(baseInstruction);

        appendEnvironmentInfo(sb, ctx);
        appendTaskDescription(sb, ctx);
        appendRecentCommands(sb, ctx);
        appendMilestones(sb, ctx);
        appendToolResultSummary(sb, ctx);
        appendLongTermMemorySummary(sb, ctx);
        appendIntentLabel(sb, ctx);

        String result = sb.toString();
        log.debug("动态 Prompt 构建完成，长度: {} (基础: {}, 动态: {})",
                result.length(), baseInstruction.length(), result.length() - baseInstruction.length());
        return result;
    }

    /**
     * 构建稳定上下文前缀。
     * 
     * 只放置低频变化的内容：任务目标、用户级长期记忆。实时工具输出和 cwd 等字段
     * 不应进入这一层，否则会造成跨轮 Prompt 前缀频繁变化。
     */
    public String buildStableContext(PromptContextVO ctx) {
        if (ctx == null) return "";

        String taskDescription = stringValue(ctx.getStableContext(), "taskDescription", ctx.getTaskDescription());
        String longTermMemorySummary = stringValue(ctx.getStableContext(), "longTermMemorySummary", ctx.getLongTermMemorySummary());

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        if (!isEmpty(taskDescription)) {
            sb.append("[当前任务]\n").append(taskDescription).append("\n");
            hasContent = true;
        }

        if (!isEmpty(longTermMemorySummary)) {
            if (hasContent) sb.append("\n");
            sb.append("[长期记忆]\n").append(longTermMemorySummary).append("\n");
            hasContent = true;
        }

        if (!hasContent) return "";
        return sb.toString();
    }

    /**
     * 构建动态上下文尾部。
     * 
     * 放置每轮可能变化的内容，保证稳定前缀之后的上下文只在尾部演进。
     */
    public String buildEphemeralContext(PromptContextVO ctx) {
        if (ctx == null) return "";

        Map<String, Object> context = ctx.getEphemeralContext();
        String osInfo = stringValue(context, "osInfo", ctx.getOsInfo());
        String toolResultSummary = stringValue(context, "toolResultSummary", ctx.getToolResultSummary());
        List<MilestoneVO> milestoneVOS = milestones(context, ctx.getMilestoneVOS());

        StringBuilder sb = new StringBuilder();
        boolean hasContent = false;

        if (!isEmpty(osInfo)) {
            sb.append("[\n系统环境]\n");
            if (!isEmpty(osInfo)) sb.append("系统: ").append(osInfo).append("\n");
            hasContent = true;
        }

        if (ctx.getRecentCommands() != null && !ctx.getRecentCommands().isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append("[最近执行的任务]\n");
            for (String cmd : ctx.getRecentCommands()) {
                sb.append("- ").append(cmd).append("\n");
            }
            hasContent = true;
        }

        if (milestoneVOS != null && !milestoneVOS.isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append("[关键事件]\n");
            for (MilestoneVO milestone : milestoneVOS) {
                sb.append("- [").append(milestone.getType().name()).append("] ")
                        .append(milestone.getContent()).append("\n");
            }
            hasContent = true;
        }

        if (!isEmpty(toolResultSummary)) {
            if (hasContent) sb.append("\n");
            sb.append("[工具执行摘要]\n").append(toolResultSummary).append("\n");
            hasContent = true;
        }

        String intentHint = buildIntentHint(ctx);
        if (!intentHint.isEmpty()) {
            if (hasContent) sb.append("\n");
            sb.append(intentHint);
            hasContent = true;
        }

        return hasContent ? sb.toString() : "";
    }

    private String buildIntentHint(PromptContextVO ctx) {
        IntentResultVO result = ctx.getIntentResult();
        if (result != null && result.getIntent() == intentRegistry.getUnknown() && result.getConfidence() < 0.5) {
            return "";
        }

        String primary = result != null && result.getIntent() != null
                ? result.getIntent().name()
                : ctx.getIntentLabel();
        if (isEmpty(primary)) {
            return "";
        }

        StringBuilder sb = new StringBuilder("[意图提示]\n")
                .append("- 主要意图: ").append(primary);
        if (result != null) {
            sb.append(" (置信度: ").append(Math.round(result.getConfidence() * 100)).append("%)");
        }
        sb.append("\n");

        if (result != null && result.getCandidateIntents() != null && !result.getCandidateIntents().isEmpty()) {
            sb.append("- 备选: ").append(result.getCandidateIntents().stream()
                    .map(IntentType::name)
                    .collect(java.util.stream.Collectors.joining(", "))).append("\n");
        }
        if (result != null && result.getEntities() != null && !result.getEntities().isEmpty()) {
            sb.append("- 实体: ").append(result.getEntities().entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(", "))).append("\n");
        }
        sb.append("- 使用原则: 该结果仅作参考，不得覆盖用户消息、历史结论和工具证据。\n");
        log.info("意图提示: {}, confidence: {}", primary, result == null ? "n/a" : result.getConfidence());
        return sb.toString();
    }

    /**
     * 追加当前任务描述段落（优先从 TaskStateVO 获取，比从消息历史推断更准确）
     */
    private void appendTaskDescription(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getTaskDescription())) {
            return;
        }
        sb.append("\n\n## 当前任务\n");
        sb.append(ctx.getTaskDescription()).append("\n");
    }

    /**
     * 追加工具执行摘要段落
     */
    private void appendToolResultSummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getToolResultSummary())) {
            return;
        }
        sb.append("\n\n## 工具执行摘要\n");
        sb.append(ctx.getToolResultSummary()).append("\n");
    }

    /**
     * 追加长期记忆段落。
     * 
     * 将 LongTermMemoryProvider 召回的长期记忆摘要渲染为 [长期记忆] 段落，
     * 拼到用户消息后面，让主模型感知用户偏好、环境信息、软件版本、排查经验等。
     */
    private void appendLongTermMemorySummary(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getLongTermMemorySummary())) {
            return;
        }
        sb.append("\n\n## 长期记忆\n");
        sb.append(ctx.getLongTermMemorySummary()).append("\n");
    }

    /**
     * 追加用户意图标签段落
     */
    private void appendIntentLabel(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getIntentLabel())) {
            return;
        }
        sb.append("\n\n## 用户意图\n");
        sb.append(ctx.getIntentLabel()).append("\n");
    }

    /**
     * 将环境信息以 Markdown 格式追加到 StringBuilder 中。
     * 
     * 服务器、操作系统、当前用户、工作目录四个字段全为空时跳过，不输出空标题。
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendEnvironmentInfo(StringBuilder sb, PromptContextVO ctx) {
        if (isEmpty(ctx.getOsInfo())) {
            return;
        }
        sb.append("\n\n## 当前环境信息\n");
        if (!isEmpty(ctx.getOsInfo())) sb.append("- 操作系统: ").append(ctx.getOsInfo()).append("\n");
    }

    /**
     * 将最近执行的命令列表以 Markdown 列表格式追加到 StringBuilder 中。
     * 
     * 命令列表为 null 或空时跳过。
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendRecentCommands(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getRecentCommands() == null || ctx.getRecentCommands().isEmpty()) return;
        sb.append("\n## 最近操作记录\n");
        for (String cmd : ctx.getRecentCommands()) {
            sb.append("- ").append(cmd).append("\n");
        }
    }

    /**
     * 将里程碑事件列表以 Markdown 列表格式追加到 StringBuilder 中。
     * 
     * 每条里程碑格式为：{@code [TYPE] content}
     * 
     * 案例：
     * 
     *   ctx.getMilestoneVOS() = [
     *     { type=ERROR, content="permission denied" },
     *     { type=TASK_CHANGE, content="换个思路看 access.log" }
     *   ]
     *
     *   追加结果：
     *   "\n## 关键事件\n- [ERROR] permission denied\n- [TASK_CHANGE] 换个思路看 access.log\n"
     * 
     *
     * @param sb  目标 StringBuilder
     * @param ctx 动态上下文
     */
    private void appendMilestones(StringBuilder sb, PromptContextVO ctx) {
        if (ctx.getMilestoneVOS() == null || ctx.getMilestoneVOS().isEmpty()) return;
        sb.append("\n## 关键事件\n");
        for (MilestoneVO m : ctx.getMilestoneVOS()) {
            sb.append("- [").append(m.getType().name()).append("] ").append(m.getContent()).append("\n");
        }
    }

    /**
     * 判断字符串是否为 null 或空白。
     *
     * @param s 待检查字符串
     * @return true 表示为 null 或全空白
     */
    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String stringValue(Map<String, Object> context, String key, String fallback) {
        Object value = context == null ? null : context.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<MilestoneVO> milestones(Map<String, Object> context, List<MilestoneVO> fallback) {
        Object value = context == null ? null : context.get("milestoneVOS");
        if (value instanceof List<?> list) {
            return (List<MilestoneVO>) list;
        }
        return fallback;
    }
}
