package com.zwx.zwxagent.agent;

import com.zwx.zwxagent.conversation.AgentConversationMessage;
import com.zwx.zwxagent.security.CurrentActor;
import com.zwx.zwxagent.security.Role;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

@SpringBootTest
class ZwxManusTest {

    @Resource
    private com.zwx.zwxagent.tools.ToolFactory toolFactory;

    @Resource
    private ChatModel dashscopeChatModel;

    @Test
    public void run() {
        ZwxManus zwxManus = new ZwxManus(toolFactory.createTools("shared"), dashscopeChatModel);
        zwxManus.restoreHistory(List.<AgentConversationMessage>of());
        String userPrompt = """
                我的另一半居住在上海静安区，请帮我找到 5 公里内合适的约会地点，
                并结合一些网络图片，制定一份详细的约会计划，
                并以 PDF 格式输出""";
        String answer = zwxManus.run(userPrompt);
        Assertions.assertNotNull(answer);
    }
}
