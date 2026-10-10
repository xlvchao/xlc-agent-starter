## 项目简介
为了降低Agent开发门槛、提升业务系统集成效率，基于 DDD领域设计驱动 + Spring AI + Google ADK 构建的AI Agent开发脚手架，支持从YAML配置文件动态装配智能体，支持mcp、skills、plugin插件扩展能力，支持顺序、并行、循环Agent以及基于依赖关系的动态子Agent编排。此外还实现了ReAct执行链路、意图识别、历史消息剪裁与Token控制、Prompt动态增强、长期记忆，以及基于SSE的全链路流式输出。


## 技术选型
| 技术 <img width=300/>| 说明                                     | 网站 |
|-------------|----------------------------------------|----|
| DDD领域驱动设计   | 本项目严格按照DDD设计思想分成7层                     |   https://domain-driven-design.org/zh/ddd-design-workshop-guide.html |
| Spring Boot | As you know               |   https://spring.io/projects/spring-boot |
| Spring AI   | Spring开源Agent开发框架，本项目中主要使用它的基础Agent构建能力 |  https://spring.io/projects/spring-ai |
| Google ADK  | Google开源Agent开发框架，本项目中主要使用它的Agent编排能力  | https://adk.dev/get-started  |
| Guava       | Google开源本地缓存框架                         |  https://github.com/google/guava |
| SSE         | Spring-web默认支持，主要用来实现聊天时的流式输出（类似打字机效果） |  https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html#mvc-ann-async-sse |
| Mysql       | Agent会话存储与查询、实时聊天记录、长期记忆的存储与召回         |  https://www.mysql.com  |


## 项目结构
![架构图](https://tuchuang-57s.pages.dev/jiagoutu.png)

**核心特性**
1. Agent自动化装配（YAML配置 + 责任链&策略树）
2. 多智能体协作（子智能体派发，基于Agent-as-Tool多智能体协作机制实现）
3. Agent 工作流编排（支持loop / parallel / sequential）
4. 支持 MCP（SSE / Stdio / Local）、Skills、Tool
5. ReAct 任务编排（自定义ReAct业务执行链路，不参与真实ReAct循环）
6. Prompt动态增强（信息越用越全）
7. 历史消息剪裁与Token控制（保证长对话/复杂任务下AI仍然 "记得住重点" 且成本可控）
8. 意图识别（规则分类 + LLM 分类）
9. 长短期记忆（long_term_memory / 自定义SessionService）
10. 工具调用（为智能体工具调用提供兜底能力）
11. 缓存命中与推理强度（静态前置、动态后置，最大程度命中缓存，节省Token）
12. 同时支持 HTTP 与 SSE 流式对话（支持文本、文件、图片满足多种需求场景）

**一句话总结**：实现了一个分工明确（Agent-as-Tool），能边做边想（ReAct）、信息越用越全（动态富化）、不会失忆也不会撑爆（缓存命中与推理强度 + 剪裁 + Token 控制）、越聊越懂你（意图 + 反馈）、并且能长期记住重要事情（记忆 + 召回）的智能体系统框架！

## 模块划分
```
xlc-agent-starter
 ├── xlc-agent-starter-app -- 服务端启动入口、智能体自动装配启动
 ├── xlc-agent-starter-case -- ReAct业务链路编排：流式对话
 ├── xlc-agent-starter-core -- 公共核心资源
 ├── xlc-agent-starter-domain -- 领域层：智能体装配/长短期记忆/意图识别/动态上下文/会话与对话（HTTP）等
 ├── xlc-agent-starter-infra -- 基础设施：Mysql、Redis等交互接口
 ├── xlc-agent-starter-trigger -- 对外服务：会话接口、对话接口
 └── xlc-agent-starter-types -- 公共常量
```

## 快速体验
1、*创建库表*  
执行该脚本会创建以下4张空表：docs/mysql/xlc-agent-starter.sql
![mysql](https://tuchuang-57s.pages.dev/mysql.png)

2、*修改配置*  
修改成支持openai交互协议的大模型：xlc-agent-starter-app\src\main\resources\agent\xlc-agent.yml
![xlc-agent](https://tuchuang-57s.pages.dev/xlc-agent.png)

修改mysql配置：xlc-agent-starter-app\src\main\resources\application-dev.yml
![application-dev](https://tuchuang-57s.pages.dev/application-dev.png)


3、*启动测试*  
step-1：先启动服务端  
![app](https://tuchuang-57s.pages.dev/app.png)

step-2：通过控制台交互  
![test](https://tuchuang-57s.pages.dev/test.png)

step-3：通过接口交互
![chat_stream](https://tuchuang-57s.pages.dev/chat_stream.png)



## 开发要点
```java
Agent自动装配：xlc-agent-starter-app\src\main\java\com\xlc\ai\config\AgentInstaller.java

Agent会话、对话：xlc-agent-starter-trigger\src\main\java\com\xlc\ai\trigger\http\AgentServiceController.java

Tool开发样例：xlc-agent-starter-domain\src\main\java\com\xlc\ai\domain\agent\service\install\matter\tools\SampleAdkTool.java

Skills扩展目录：xlc-agent-starter-app\src\main\resources\agent\skills

大模型/Prompt/MCP/Skills/Plugin/工作流配置：xlc-agent-starter-app\src\main\resources\agent\xlc-agent.yml
```

# 公众号
关注不迷路，微信扫描下方二维码关注公众号「**南山有一郎**」，时刻收听**项目更新**通知！

在公众号后台回复“**加群**”，即可加入「**南山有一郎**」交流群！

![mp_qrcode](https://tuchuang-57s.pages.dev/mp_qrcode_2.jpg)


# 许可证
```
Copyright [2026] [xlvchao]

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
