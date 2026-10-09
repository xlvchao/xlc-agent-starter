package com.xlc.ai.domain.agent.service.install.matter.tools;

import com.google.adk.tools.Annotations.Schema;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.xlc.ai.domain.agent.model.valobj.AgentConfigure;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 *
 * 使用 ADK 的 @Schema 注解定义参数，支持 FunctionTool.create()
 *
 * @author xlvchao
 */
@Slf4j
@Service
public class SampleAdkTool {

    @Resource
    private AgentConfigure agentConfigure;

    /** Shell prompt 检测用的常见标记 */
    private static final String[] PROMPT_MARKERS = {"$ ", "# ", "% "};
    private final JSch jsch = new JSch();




    public Map<String, Object> executeCommand(
            @Schema(name = "command", description = "要执行的 Shell 命令，如: ls -la, apt install docker.io, docker --version")
            String command) {
        Session session = null;
        ChannelShell channel = null;
        InputStream in = null;
        OutputStream out = null;
        StringBuilder readBuffer = new StringBuilder();

        //这里写死，后续可根据业务场景改成读配置或者数据库
        String username = "127.0.0.1";
        String password = "root";
        String host = "root";
        int port = 22;

        try {
            session = jsch.getSession(username, host, port);
            session.setConfig("StrictHostKeyChecking", "no");
            session.setConfig("ServerAliveInterval", "30");   // 每30秒发送keep-alive
            session.setConfig("ServerAliveCountMax", "3");     // 3次无响应才断开
            session.setTimeout(0); // 不设置socket超时，避免reader线程被误杀
            session.setPassword(password);
            session.connect();
            log.info("SSH连接成功");

            channel = (ChannelShell) session.openChannel("shell");
            channel.setPty(true);
            in = channel.getInputStream();
            out = channel.getOutputStream();
            channel.connect(5000);
            log.info("终端会话打开成功");

            // 启动输出读取线程，持续读取 shell 输出到缓冲区
            startOutputReader(in, readBuffer);

            out.write(command.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 等待输出稳定（使用 wait/notifyAll 机制）
            long deadline = System.currentTimeMillis() + 10000;
            int stableCount = 0;
            final int STABLE_THRESHOLD = 3; // 连续 3 次无新数据认为输出完成
            final long POLL_INTERVAL = 100; // 轮询间隔 ms

            while (System.currentTimeMillis() < deadline) {
                synchronized (readBuffer) {
                    // 等待数据到达或超时
                    long waitMs = Math.min(POLL_INTERVAL, deadline - System.currentTimeMillis());
                    if (waitMs > 0 && readBuffer.length() == 0) {
                        try {
                            readBuffer.wait(waitMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (readBuffer.length() > 0) {
                        stableCount = 0; // 有新数据，重置稳定计数
                    } else {
                        stableCount++;
                    }
                }
                // 检查是否输出稳定
                if (stableCount >= STABLE_THRESHOLD) {
                    String output;
                    synchronized (readBuffer) {
                        output = readBuffer.toString();
                    }
                    if (output.length() > 0 && containsPrompt(output)) {
                        log.info("[executeCommandAndWaitOutput] 输出稳定(检测到prompt) outputLength={}", output.length());
                        break;
                    }
                    // 没有 prompt 但稳定了很久，也认为完成
                    if (stableCount >= STABLE_THRESHOLD * 3) {
                        log.info("[executeCommandAndWaitOutput] 输出稳定(无prompt超时)");
                        break;
                    }
                }
            }

            // 收集最终结果
            String result;
            synchronized (readBuffer) {
                result = readBuffer.toString();
                readBuffer.setLength(0);
            }

            // 5. 清理输出
            result = cleanCommandOutput(result, command);

            log.info("[executeCommandAndWaitOutput] 完成 resultLength={} resultPreview={}",
                    result.length(),
                    result.length() > 200 ? result.substring(0, 200) + "..." : result);

            Map<String, Object> map = new java.util.HashMap<>();
            map.put("command", command);
            map.put("output", result);
            map.put("success", true);
            return map;

        } catch (JSchException e) {
            log.error("SSH连接失败 error={}", e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                if(out != null) out.close();
                if(in != null) in.close();
                if(channel != null) channel.disconnect();
                if(session != null) session.disconnect();
            } catch (Exception e){
                e.printStackTrace();
            }
        }

        Map<String, Object> map = new java.util.HashMap<>();
        map.put("command", command);
        map.put("output", "Error: 执行失败");
        map.put("success", false);
        return map;
    }

    /**
     * 清理命令输出：
     * - 去除第一行命令回显
     * - 去除末尾 prompt 行
     * - 去除 ANSI 控制序列
     */
    private String cleanCommandOutput(String output, String command) {
        if (output == null || output.isEmpty()) return "";

        // 去除 ANSI 转义序列
        String cleaned = output.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        // 去除回车符
        cleaned = cleaned.replace("\r", "");

        // 按行分割
        String[] lines = cleaned.split("\n");
        java.util.List<String> resultLines = new java.util.ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedLine = line.trim();

            // 跳过空行
            if (trimmedLine.isEmpty()) continue;

            // 跳过第一行如果是命令回显
            if (resultLines.isEmpty() && isCommandEcho(trimmedLine, command)) {
                continue;
            }

            // 跳过末尾 prompt 行
            if (i == lines.length - 1 && isPromptLine(trimmedLine)) {
                continue;
            }

            resultLines.add(line);
        }

        return String.join("\n", resultLines).trim();
    }

    /**
     * 判断是否为命令回显行
     */
    private boolean isCommandEcho(String line, String command) {
        String cmd = command.trim();
        // 精确匹配或前缀匹配（Shell 有时会加 prompt 前缀）
        if (line.equals(cmd)) return true;
        // 处理带 prompt 前缀的情况，如 "ubuntu@host:~$ cat /etc/os-release"
        if (line.endsWith(cmd)) return true;
        // 命令很短时只做精确匹配，避免误杀
        if (cmd.length() > 10 && line.contains(cmd)) return true;
        return false;
    }

    /**
     * 判断是否为 prompt 行（如 "ubuntu@VM-0-7-ubuntu:~$"）
     */
    private boolean isPromptLine(String line) {
        for (String marker : PROMPT_MARKERS) {
            if (line.endsWith(marker.trim())) return true;
        }
        // 匹配 user@host:dir$ 格式
        if (line.matches(".*@.*:[^$]*\\$\\s*$")) return true;
        if (line.matches(".*@.*:[^#]*#\\s*$")) return true;
        return false;
    }

    /**
     * 检查输出是否包含 Shell prompt 标记
     */
    private boolean containsPrompt(String output) {
        if (output == null || output.isEmpty()) return false;
        // 检查最后 50 个字符中是否包含 prompt 标记
        String tail = output.length() > 50 ? output.substring(output.length() - 50) : output;
        for (String marker : PROMPT_MARKERS) {
            if (tail.contains(marker)) return true;
        }
        return false;
    }

    /**
     * 启动输出读取线程
     * SocketTimeoutException 时继续循环（不是真正的断连），
     * 只有 EOF（-1）或真正的 IOException 才退出
     */
    private void startOutputReader(InputStream in, StringBuilder readBuffer) {
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                int len;
                while ((len = in.read(buf)) != -1) {
                    String text = new String(buf, 0, len, StandardCharsets.UTF_8);
                    // AI命令模式：写入独立缓冲区
                    if (readBuffer != null) {
                        synchronized (readBuffer) {
                            readBuffer.append(text);
                            readBuffer.notifyAll(); // 通知等待的 executeCommandAndWait
                        }
                    }
                }
                // in.read() 返回 -1，说明 shell channel EOF
                log.warn("终端 Shell Channel EOF");
            } catch (Exception e) {
                log.debug("终端读取超时（非断连）error {}", e.getMessage());

            } finally {
                if (readBuffer != null) {
                    synchronized (readBuffer) {
                        readBuffer.notifyAll();
                    }
                }
            }
        }, "terminal-reader");
        reader.setDaemon(true);
        reader.start();
    }

}
