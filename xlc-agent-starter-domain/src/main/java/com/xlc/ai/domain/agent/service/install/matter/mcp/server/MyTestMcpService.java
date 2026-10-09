package com.xlc.ai.domain.agent.service.install.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MyTestMcpService {

    @Tool(description = "小写字母转换为大写字母")
    public XxxResponse toUpperCase(XxxRequest request) {
        XxxResponse xxxResponse = new XxxResponse();
        xxxResponse.setContent(request.getWord().toUpperCase());
        return xxxResponse;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxRequest {
        @JsonProperty(required = true, value = "word")
        @JsonPropertyDescription("英文单词，字符串，字母。例如: good,xiaofuge")
        private String word;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XxxResponse {
        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("单词转换结果")
        private String content;
    }

}
