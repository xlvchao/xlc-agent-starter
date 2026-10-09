package com.xlc.ai.domain.agent.model.entity;

import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 装配命令
 *
 * @author xlvchao
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstallCommandEntity {

    private AgentConfigure agentConfigure;

}
