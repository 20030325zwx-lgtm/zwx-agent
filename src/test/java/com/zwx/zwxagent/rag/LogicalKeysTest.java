package com.zwx.zwxagent.rag;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LogicalKeysTest {

    @Test
    void normalizesFilenameToLogicalKey() {
        Assertions.assertEquals("refund-policy", LogicalKeys.normalize("refund-policy.md"));
        Assertions.assertEquals("refund-policy", LogicalKeys.normalize("Refund-Policy.PDF"));
        Assertions.assertEquals("退款政策-refund-policy-v2", LogicalKeys.normalize("退款政策 refund-policy v2.docx"));
        // 上传接口是平铺的 multipart，没有目录概念；路径前缀一律剥掉，避免客户端伪造路径劫持版本链
        Assertions.assertEquals("refund-policy", LogicalKeys.normalize("travel/refund-policy.pdf"));
        Assertions.assertEquals("refund-policy", LogicalKeys.normalize("C:\\docs\\refund-policy.md"));
    }

    @Test
    void formatChangeKeepsSameLogicalKey() {
        Assertions.assertEquals(LogicalKeys.normalize("员工手册-leave-policy.md"), LogicalKeys.normalize("员工手册-leave-policy.pdf"));
    }

    @Test
    void fallsBackWhenNothingRemains() {
        Assertions.assertEquals("document", LogicalKeys.normalize("???"));
        Assertions.assertEquals("document", LogicalKeys.normalize(""));
        Assertions.assertEquals("document", LogicalKeys.normalize(null));
    }
}
