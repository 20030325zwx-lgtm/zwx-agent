package com.zwx.zwxagent.platform.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminUserInitializerTest {

    @Test
    void createsDefaultAdminWheneverAdminAccountIsMissing() throws Exception {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.existsByUsername("admin")).thenReturn(false);
        when(encoder.encode("admin123")).thenReturn("encoded");

        new AdminUserInitializer(repository, encoder, "admin", "").run(null);

        verify(repository).insert("default", "admin", "encoded", Role.ADMIN);
    }

    @Test
    void keepsExistingAdminUntouched() throws Exception {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.existsByUsername("admin")).thenReturn(true);

        new AdminUserInitializer(repository, encoder, "admin", "admin123").run(null);

        verify(encoder, never()).encode(anyString());
        verify(repository, never()).insert(anyString(), anyString(), anyString(), any(Role.class));
    }
}
