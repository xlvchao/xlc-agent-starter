package com.xlc.ai.test.trigger;

import com.xlc.ai.core.dto.ChatRequestDTO;
import com.xlc.ai.cases.IReActChatService;
import com.xlc.ai.domain.agent.service.IChatService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.Scanner;

/**
 * ReAct Agent 链路测试
 *
 * @author xlvchao
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ReActChatServiceTest {

    // Agent 配置（对应 xlc-agent.yml 中的 agent-id）
    private static final String AGENT_ID = "100000";
    private static final String USER_ID = "xlc";
    // =================================================================

    @Resource
    private IChatService chatService;

    @Resource
    private IReActChatService reActChatService;

    private String chatSessionId;

    @Before
    public void init() {
        log.info("========== ReAct Test 初始化开始 ==========");
        // 创建 AI 对话会话（ADK Runner 需要 sessionId 管理对话状态）
        chatSessionId = chatService.createSession(AGENT_ID, USER_ID);
        log.info("AI 对话会话已创建 chatSessionId={}", chatSessionId);
        log.info("========== ReAct Test 初始化完成，可以开始对话了 ==========\n");
    }

    @Test
    public void test_ai_shell() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\n你 > ");
            String input = scanner.nextLine().trim();

            if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                System.out.println("再见！");
                break;
            }

            if (input.isEmpty()) {
                continue;
            }

            try {

                // 构建 ReAct 请求
                ChatRequestDTO requestDTO = new ChatRequestDTO();
                requestDTO.setAgentId(AGENT_ID);
                requestDTO.setUserId(USER_ID);
                requestDTO.setSessionId(chatSessionId);
                requestDTO.setMessage(input);

                System.out.print("\nAI > ");
                // 同步调用，等待 ReAct 循环完成
                String result = reActChatService.chat(requestDTO);
                System.out.println(result);

            } catch (Exception e) {
                log.error("对话异常", e);
                System.out.println("出错: " + e.getMessage());
            }
        }

        scanner.close();
    }


}
