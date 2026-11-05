package com.zwx.zwxagent.platform.storage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 存储路由：写走主库，读在主库缺失时回退备用（迁移期旧对象仍在 OSS）。 */
class ObjectStorageRouterTest {

    private final ObjectStorage primary = mock(ObjectStorage.class);
    private final ObjectStorage fallback = mock(ObjectStorage.class);

    @Test
    void readsFromPrimaryAndNeverTouchesFallbackWhenPresent() throws Exception {
        when(primary.open("a/b.png")).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        try (var in = new ObjectStorageRouter(primary, fallback).open("a/b.png")) {
            Assertions.assertEquals(1, in.read());
        }
        verify(fallback, never()).open(anyString());
    }

    @Test
    void fallsBackWhenPrimaryReportsMissing() throws Exception {
        when(primary.open("legacy.png")).thenThrow(new ObjectNotFoundException("legacy.png"));
        when(fallback.open("legacy.png")).thenReturn(new ByteArrayInputStream(new byte[]{2}));
        try (var in = new ObjectStorageRouter(primary, fallback).open("legacy.png")) {
            Assertions.assertEquals(2, in.read());
        }
        when(primary.size("legacy.png")).thenThrow(new ObjectNotFoundException("legacy.png"));
        when(fallback.size("legacy.png")).thenReturn(42L);
        Assertions.assertEquals(42L, new ObjectStorageRouter(primary, fallback).size("legacy.png"));
    }

    @Test
    void missingWithoutFallbackPropagatesNotFound() {
        when(primary.open("gone.png")).thenThrow(new ObjectNotFoundException("gone.png"));
        Assertions.assertThrows(ObjectNotFoundException.class,
                () -> new ObjectStorageRouter(primary, null).open("gone.png"));
    }

    @Test
    void writesAndListingsUsePrimaryWhileListingUnionsFallback() {
        when(primary.listKeys("knowledge/")).thenReturn(List.of("knowledge/new.md"));
        when(fallback.listKeys("knowledge/")).thenReturn(List.of("knowledge/old.md"));
        var router = new ObjectStorageRouter(primary, fallback);
        router.putBytes("knowledge/new.md", "text/markdown", new byte[]{1});
        verify(primary).putBytes("knowledge/new.md", "text/markdown", new byte[]{1});
        verify(fallback, never()).putBytes(anyString(), anyString(), any(byte[].class));
        Assertions.assertEquals(List.of("knowledge/new.md", "knowledge/old.md"), router.listKeys("knowledge/"));
    }

    @Test
    void deleteRemovesFromBothBackends() {
        new ObjectStorageRouter(primary, fallback).delete("workflow/u/r/a.png");
        verify(primary).delete("workflow/u/r/a.png");
        verify(fallback).delete("workflow/u/r/a.png");
    }

    @Test
    void presignedUrlPrefersPrimaryAndFallsBackToEmpty() {
        when(primary.presignedGetUrl(eq("k"), any(Duration.class), eq((String) null))).thenReturn(Optional.empty());
        when(fallback.presignedGetUrl(eq("k"), any(Duration.class), eq((String) null)))
                .thenReturn(Optional.of("https://oss/k"));
        Assertions.assertEquals(Optional.of("https://oss/k"),
                new ObjectStorageRouter(primary, fallback).presignedGetUrl("k", Duration.ofMinutes(5), null));
    }
}
