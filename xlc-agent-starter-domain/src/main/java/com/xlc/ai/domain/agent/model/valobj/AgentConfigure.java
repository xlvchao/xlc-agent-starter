package com.xlc.ai.domain.agent.model.valobj;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "ai", ignoreInvalidFields = true)
public class AgentConfigure {

    /**
     * 是否启用AI Agent自动装配
     */
    private boolean enabled = false;

    /**
     * 智能体应用配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

        /**
         * 智能体ID
         */
        private String agentId;

        /**
         * 智能体名称
         */
        private String agentName;

        /**
         * 智能体描述
         */
        private String agentDesc;

    }

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        /**
         * 未知意图标识（代码逻辑强依赖）
         */
        private String unknownValue = "UNKNOWN";

        /**
         * 复合指令意图标识（代码逻辑强依赖）
         */
        private String compoundValue = "COMPOUND";

        /**
         * 继续意图标识（代码逻辑强依赖）
         */
        private String continueValue = "CONTINUE";

        /**
         * 闲聊意图标识（代码逻辑强依赖）
         */
        private String chatValue = "CHAT";

        /**
         * 意图列表：value + label 必填；业务意图额外配置 keywords/patterns 供规则分类
         */
        private List<Intent> commonIntents = new ArrayList<>();

        private String llmIntentClassifyPrompt;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/v1/chat/completions";
            private String embeddingsPath = "/v1/embeddings";
        }

        @Data
        public static class ChatModel {

            private String model;

            /**
             * 推理强度；minimal、low、medium、high（仅推理模型生效，非推理模型忽略）
             */
            private String reasoningEffort;

            private List<ToolMcp> toolMcpList;

            private List<ToolSkills> toolSkillsList;

            @Data
            public static class ToolMcp {

                private SSEServerParameters sse;

                private StdioServerParameters stdio;

                private LocalParameters local;

                @Data
                public static class SSEServerParameters {
                    private String name;
                    private String baseUri;
                    private String sseEndpoint;
                    private Integer requestTimeout = 3000;

                }

                @Data
                public static class StdioServerParameters {
                    private String name;
                    private Integer requestTimeout = 3000;
                    private ServerParameters serverParameters;

                    @Data
                    public static class ServerParameters {
                        private String command;
                        private List<String> args;
                        private Map<String, String> env;

                    }
                }

                @Data
                public static class LocalParameters {
                    private String name;
                }

            }

            @Data
            public static class ToolSkills {

                /**
                 * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
                 */
                private String type = "directory";

                /**
                 * 路径；
                 */
                private String path;

            }

        }

        @Data
        public static class Intent {
            private String value;
            private String label;
            private List<String> keywords = new ArrayList<>();
            private List<String> patterns = new ArrayList<>();
        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;
            private List<String> subAgents;

        }

        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }
}
