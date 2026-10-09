package com.xlc.ai.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求DTO
 *
 * @author xlvchao
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String message;


}
