package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.platform.security.JwtService;
import com.zwx.zwxagent.platform.security.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AuthControllerTest {

    @Test
    void rejectsRegistrationWhenDisabled() {
        AuthController controller = new AuthController(
                mock(UserRepository.class), mock(PasswordEncoder.class), mock(JwtService.class), false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.register(new AuthController.AuthRequest("new-user", "password")));

        assertEquals(403, exception.getStatusCode().value());
    }
}
