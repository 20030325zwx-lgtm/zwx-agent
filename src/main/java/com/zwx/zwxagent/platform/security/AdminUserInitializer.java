package com.zwx.zwxagent.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminUserInitializer implements ApplicationRunner {

    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

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
        String effectivePassword = adminPassword == null || adminPassword.isBlank()
                ? DEFAULT_ADMIN_PASSWORD
                : adminPassword;
        userRepository.insert("default", adminUsername, passwordEncoder.encode(effectivePassword), Role.ADMIN);
        if (DEFAULT_ADMIN_PASSWORD.equals(effectivePassword)) {
            org.slf4j.LoggerFactory.getLogger(AdminUserInitializer.class).warn(
                    "Bootstrapped admin user '{}' with default password 'admin123'. Set APP_SECURITY_ADMIN_PASSWORD to override before any real deployment.",
                    adminUsername);
        }
    }
}
