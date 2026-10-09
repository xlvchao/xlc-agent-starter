package com.xlc.ai.domain.agent.service.install.matter.mcp;

import com.xlc.ai.domain.agent.service.install.matter.mcp.server.MyTestMcpService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class LocalToolMcpRegister {

    /**
     * 本地大小写转换工具
     */
    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService toolService) {
        return MethodToolCallbackProvider.builder().toolObjects(toolService).build();
    }
}
