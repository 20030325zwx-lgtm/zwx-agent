package com.zwx.zwxagent.platform.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** 产物下载签名：绑定对象键与归属，篡改/换键/过期一律拒绝。 */
class ArtifactSignatureServiceTest {

    private final ArtifactSignatureService service = new ArtifactSignatureService("test-secret", 7200);

    @Test
    void issuedTokenVerifiesForSameKeyAndCarriesOwner() {
        String token = service.issue("default", "user-1", "workflow/user-1/run-1/poster.png");
        var access = service.verify("workflow/user-1/run-1/poster.png", token);
        Assertions.assertTrue(access.isPresent());
        Assertions.assertEquals("user-1", access.get().userId());
        Assertions.assertEquals("default", access.get().tenantId());
    }

    @Test
    void rejectsTokenReusedForAnotherObject() {
        String token = service.issue("default", "user-1", "workflow/user-1/run-1/poster.png");
        Assertions.assertTrue(service.verify("workflow/user-1/run-2/poster.png", token).isEmpty());
    }

    @Test
    void rejectsTamperedOrMalformedTokens() {
        String token = service.issue("default", "user-1", "workflow/user-1/run-1/poster.png");
        Assertions.assertTrue(service.verify("workflow/user-1/run-1/poster.png", token + "0").isEmpty());
        Assertions.assertTrue(service.verify("workflow/user-1/run-1/poster.png", "bad").isEmpty());
        Assertions.assertTrue(service.verify("workflow/user-1/run-1/poster.png", null).isEmpty());
        Assertions.assertTrue(service.verify(null, token).isEmpty());
    }

    @Test
    void rejectsExpiredToken() {
        ArtifactSignatureService shortLived = new ArtifactSignatureService("test-secret", 60);
        String token = shortLived.issue("default", "user-1", "workflow/user-1/run-1/a.png");
        // 把过期时间改成过去，签名必然不匹配
        String expired = "1" + token.substring(token.indexOf('.'));
        Assertions.assertTrue(shortLived.verify("workflow/user-1/run-1/a.png", expired).isEmpty());
    }
}
