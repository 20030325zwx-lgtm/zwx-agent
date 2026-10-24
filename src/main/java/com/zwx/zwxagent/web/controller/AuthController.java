package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.platform.security.AppUser;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.JwtService;
import com.zwx.zwxagent.platform.security.Role;
import com.zwx.zwxagent.platform.security.UserRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@RestController
@RequestMapping("/auth")
@Validated
public class AuthController {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{3,64}");
    private static final Pattern TENANT_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public record AuthRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record AuthResponse(String token, String username, String tenantId, String role) {
    }

    @PostMapping("/register")
    public AuthResponse register(@RequestBody AuthRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username must be 3-64 characters of letters, digits, _, . or -");
        }
        if (request.password() == null || request.password().length() < 6 || request.password().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be 6-128 characters");
        }
        if (userRepository.existsByUsername(username)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        AppUser user = userRepository.insert("default", username, passwordEncoder.encode(request.password()), Role.USER);
        CurrentActor actor = new CurrentActor(user.id(), user.tenantId(), user.username(), user.role());
        return toResponse(jwtService.issue(actor), actor);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        return authenticate(request.username(), request.password());
    }

    @PostMapping("/token")
    public AuthResponse token(jakarta.servlet.http.HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Basic ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Basic credentials required");
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator < 1) throw new IllegalArgumentException("Invalid Basic credentials");
            return authenticate(decoded.substring(0, separator), decoded.substring(separator + 1));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
    }

    private AuthResponse authenticate(String rawUsername, String rawPassword) {
        String username = rawUsername == null ? "" : rawUsername.trim();
        AppUser user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password"));
        if (!"ACTIVE".equals(user.status())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is disabled");
        }
        if (!passwordEncoder.matches(rawPassword == null ? "" : rawPassword, user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        CurrentActor actor = new CurrentActor(user.id(), user.tenantId(), user.username(), user.role());
        return toResponse(jwtService.issue(actor), actor);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(CurrentActor actor) {
        return toResponse(jwtService.issue(actor), actor);
    }

    @GetMapping("/me")
    public AuthResponse me(CurrentActor actor) {
        return toResponse(null, actor);
    }

    private AuthResponse toResponse(String token, CurrentActor actor) {
        return new AuthResponse(token, actor.username(), actor.tenantId(), actor.role().name());
    }
}
