package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.platform.security.AppUser;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.JwtService;
import com.zwx.zwxagent.platform.security.Role;
import com.zwx.zwxagent.platform.security.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void rejectsRegistrationWhenDisabled() {
        AuthController controller = new AuthController(
                mock(UserRepository.class), mock(PasswordEncoder.class), mock(JwtService.class), false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.register(new AuthController.AuthRequest("new-user", "password")));

        assertEquals(403, exception.getStatusCode().value());
    }

    @Test
    void provisioningUserRequiresAdmin() {
        AuthController controller = new AuthController(
                mock(UserRepository.class), mock(PasswordEncoder.class), mock(JwtService.class), false);
        CurrentActor regular = new CurrentActor("u-1", "default", "bob", Role.USER);

        assertThrows(AccessDeniedException.class,
                () -> controller.createUser(regular, new AuthController.AuthRequest("eval-bot", "eval-bot-local")));
    }

    @Test
    void adminCanProvisionIsolatedUserInOwnTenant() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.existsByUsername("eval-bot")).thenReturn(false);
        when(encoder.encode("eval-bot-local")).thenReturn("hash");
        when(repository.insert("default", "eval-bot", "hash", Role.USER))
                .thenReturn(new AppUser("id-1", "default", "eval-bot", "hash", Role.USER, "ACTIVE"));
        AuthController controller = new AuthController(
                repository, encoder, mock(JwtService.class), false);
        CurrentActor admin = new CurrentActor("admin-1", "default", "admin", Role.ADMIN);

        AuthController.UserView view = controller.createUser(admin,
                new AuthController.AuthRequest("eval-bot", "eval-bot-local"));

        assertEquals("eval-bot", view.username());
        assertEquals("default", view.tenantId());
        assertEquals("USER", view.role());
        assertEquals("ACTIVE", view.status());
    }

    @Test
    void adminCanProvisionAdministratorRole() {
        UserRepository repository = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(repository.existsByUsername("ops-admin")).thenReturn(false);
        when(encoder.encode("secret-pass")).thenReturn("hash");
        when(repository.insert("default", "ops-admin", "hash", Role.ADMIN))
                .thenReturn(new AppUser("id-2", "default", "ops-admin", "hash", Role.ADMIN, "ACTIVE"));
        AuthController controller = new AuthController(repository, encoder, mock(JwtService.class), false);
        CurrentActor admin = new CurrentActor("admin-1", "default", "admin", Role.ADMIN);

        AuthController.UserView view = controller.createUser(admin,
                new AuthController.AuthRequest("ops-admin", "secret-pass", "ADMIN"));

        assertEquals("ADMIN", view.role());
    }

    @Test
    void rejectsUnknownProvisioningRole() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.existsByUsername("someone")).thenReturn(false);
        AuthController controller = new AuthController(
                repository, mock(PasswordEncoder.class), mock(JwtService.class), false);
        CurrentActor admin = new CurrentActor("admin-1", "default", "admin", Role.ADMIN);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.createUser(admin, new AuthController.AuthRequest("someone", "secret-pass", "ROOT")));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void listingUsersRequiresAdmin() {
        AuthController controller = new AuthController(
                mock(UserRepository.class), mock(PasswordEncoder.class), mock(JwtService.class), false);
        CurrentActor regular = new CurrentActor("u-1", "default", "bob", Role.USER);

        assertThrows(AccessDeniedException.class, () -> controller.listUsers(regular));
    }

    @Test
    void adminCannotDeleteSelf() {
        AuthController controller = new AuthController(
                mock(UserRepository.class), mock(PasswordEncoder.class), mock(JwtService.class), false);
        CurrentActor admin = new CurrentActor("admin-1", "default", "admin", Role.ADMIN);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.deleteUser(admin, "admin-1"));

        assertEquals(400, exception.getStatusCode().value());
    }

    @Test
    void adminCannotDeleteLastAdministrator() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findById("admin-2"))
                .thenReturn(java.util.Optional.of(new AppUser("admin-2", "default", "ops", "hash", Role.ADMIN, "ACTIVE")));
        when(repository.countByTenantAndRole("default", Role.ADMIN)).thenReturn(1L);
        AuthController controller = new AuthController(
                repository, mock(PasswordEncoder.class), mock(JwtService.class), false);
        CurrentActor admin = new CurrentActor("admin-1", "default", "admin", Role.ADMIN);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.deleteUser(admin, "admin-2"));

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void adminCanDeleteUserInOwnTenant() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findById("u-2"))
                .thenReturn(java.util.Optional.of(new AppUser("u-2", "default", "bob", "hash", Role.USER, "ACTIVE")));
        when(repository.deleteById("u-2", "default")).thenReturn(true);
        AuthController controller = new AuthController(
                repository, mock(PasswordEncoder.class), mock(JwtService.class), false);
        CurrentActor admin = new CurrentActor("admin-1", "default", "admin", Role.ADMIN);

        controller.deleteUser(admin, "u-2");

        org.mockito.Mockito.verify(repository).deleteById("u-2", "default");
    }

    @Test
    void adminCannotDeleteUserFromAnotherTenant() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findById("u-3"))
                .thenReturn(java.util.Optional.of(new AppUser("u-3", "other", "eve", "hash", Role.USER, "ACTIVE")));
        AuthController controller = new AuthController(
                repository, mock(PasswordEncoder.class), mock(JwtService.class), false);
        CurrentActor admin = new CurrentActor("admin-1", "default", "admin", Role.ADMIN);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.deleteUser(admin, "u-3"));

        assertEquals(404, exception.getStatusCode().value());
    }
}
