package com.xlc.ai.core.dto;

import lombok.Data;

/**
 * 创建会话请求DTO
 *
 * @author xlvchao
 */
@Data
public class CreateSessionRequestDTO {

    private String agentId;

    private String userId;

}
