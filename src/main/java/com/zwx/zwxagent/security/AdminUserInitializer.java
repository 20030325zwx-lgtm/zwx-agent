package com.zwx.zwxagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminUserInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public AdminUserInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                @Value("${app.security.admin-username:admin}") String adminUsername,
                                @Value("${app.security.admin-password:}") String adminPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByUsername(adminUsername)) return;
        if (adminPassword == null || adminPassword.isBlank()) {
            if (userRepository.count() > 0) return;
            userRepository.insert("default", adminUsername, passwordEncoder.encode("admin123"), Role.ADMIN);
            org.slf4j.LoggerFactory.getLogger(AdminUserInitializer.class).warn(
                    "Bootstrapped admin user '{}' with default password 'admin123'. Set APP_SECURITY_ADMIN_PASSWORD to override before any real deployment.",
                    adminUsername);
            return;
        }
        userRepository.insert("default", adminUsername, passwordEncoder.encode(adminPassword), Role.ADMIN);
    }
}
