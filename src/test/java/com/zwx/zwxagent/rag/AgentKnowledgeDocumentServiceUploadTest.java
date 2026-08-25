package com.zwx.zwxagent.rag;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.zwx.zwxagent.storage.OssClientProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentKnowledgeDocumentServiceUploadTest {

    @Test
    void uploadsKnowledgeToOssWithoutDeletingExistingObjects() {
        OssClientProvider ossClientProvider = mock(OssClientProvider.class);
        OSS oss = mock(OSS.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(ossClientProvider.getClient()).thenReturn(oss);
        when(jdbcTemplate.queryForObject(contains("SELECT id, tenant_id"), any(RowMapper.class), any(), any(), any()))
                .thenReturn(new AgentKnowledgeDocument("document-id", "default", "love", "sample.md", "PENDING", 0, null, Instant.now(), null));

        AgentKnowledgeDocumentService service = new AgentKnowledgeDocumentService(
                ossClientProvider, "zwx-agent", jdbcTemplate, mock(VectorStore.class), new MyTokenTextSplitter(), mock(DocumentParsingModule.class));

        service.upload("default", "love", new MockMultipartFile("file", "sample.md", "text/markdown", "# Test\nKnowledge content".getBytes()));

        verify(oss).putObject(eq("zwx-agent"), contains("knowledge/default/love/"), any(InputStream.class), any(ObjectMetadata.class));
        verify(jdbcTemplate).update(contains("INSERT INTO agent_knowledge_document"), any(), any(), any(), any(), any());
        verify(oss, never()).deleteObject(any(String.class), any(String.class));
    }
}
