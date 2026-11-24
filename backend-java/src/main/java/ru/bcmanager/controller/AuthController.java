package ru.bcmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import ru.bcmanager.domain.User;
import ru.bcmanager.domain.enums.Role;
import ru.bcmanager.dto.AuthDtos;
import ru.bcmanager.repository.UserRepository;
import ru.bcmanager.security.JwtService;

/**
 * Аутентификация и регистрация по логину и паролю (Plan.md, раздел 5.1).
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Аутентификация", description = "Вход, регистрация и данные текущего пользователя")
public class AuthController {

    private final AuthenticationManager authenticationManager;

    private final JwtService jwtService;

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    /**
     * Создать контроллер аутентификации.
     *
     * @param authenticationManager менеджер проверки учётных данных
     * @param jwtService            сервис выпуска токенов
     * @param userRepository        репозиторий пользователей
     * @param passwordEncoder       кодировщик паролей
     */
    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Вход в систему.
     *
     * @param request логин и пароль
     * @return JWT и данные пользователя
     */
    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Вход в систему", security = {})
    public ResponseEntity<AuthDtos.LoginResponse> login(
            @Valid @RequestBody AuthDtos.LoginRequest request) {
        String username = request.username().trim();
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        username, request.password()));
        User user = requireUser(authentication.getName());
        String token = jwtService.generateToken(user);
        return ResponseEntity.ok(new AuthDtos.LoginResponse(
                token,
                "Bearer",
                user.getUsername(),
                user.getFullName(),
                user.getRole().name(),
                jwtService.getExpirationMinutes()));
    }

    /**
     * Регистрация нового пользователя (логин и пароль, без почты).
     * Создаётся пустой персональный аккаунт с полными правами управления своим БЦ.
     *
     * @param request логин и пароль
     * @return JWT и данные созданного пользователя
     */
    @PostMapping("/register")
    @SecurityRequirements
    @Transactional
    @Operation(summary = "Регистрация нового пользователя", security = {})
    public ResponseEntity<AuthDtos.LoginResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request) {
        String username = request.username().trim();
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalStateException("Логин «" + username + "» уже занят");
        }
        String fullName = request.fullName() != null && !request.fullName().isBlank()
                ? request.fullName().trim()
                : username;
        User user = new User(
                username,
                passwordEncoder.encode(request.password()),
                fullName,
                Role.ADMIN);
        user.setPersonalWorkspace(true);
        User saved = userRepository.save(user);
        String token = jwtService.generateToken(saved);
        return ResponseEntity.status(HttpStatus.CREATED).body(new AuthDtos.LoginResponse(
                token,
                "Bearer",
                saved.getUsername(),
                saved.getFullName(),
                saved.getRole().name(),
                jwtService.getExpirationMinutes()));
    }

    /**
     * Данные текущего авторизованного пользователя.
     *
     * @param authentication текущая аутентификация
     * @return логин, ФИО и роль
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Текущий пользователь")
    public AuthDtos.CurrentUserResponse me(Authentication authentication) {
        if (authentication == null) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "Требуется авторизация");
        }
        User user = requireUser(authentication.getName());
        return new AuthDtos.CurrentUserResponse(
                user.getUsername(), user.getFullName(), user.getRole().name());
    }

    /**
     * Смена пароля текущего авторизованного пользователя.
     *
     * @param request        текущий и новый пароли
     * @param authentication текущая аутентификация
     * @return обновлённый JWT и данные пользователя
     */
    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    @Transactional
    @Operation(summary = "Смена пароля текущего пользователя")
    public ResponseEntity<AuthDtos.LoginResponse> changePassword(
            @Valid @RequestBody AuthDtos.ChangePasswordRequest request,
            Authentication authentication) {
        if (authentication == null) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "Требуется авторизация");
        }
        User user = requireUser(authentication.getName());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Неверный текущий пароль");
        }
        if (request.newPassword().equals(request.currentPassword())) {
            throw new IllegalArgumentException("Новый пароль должен отличаться от текущего");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        User saved = userRepository.save(user);
        String token = jwtService.generateToken(saved);
        return ResponseEntity.ok(new AuthDtos.LoginResponse(
                token,
                "Bearer",
                saved.getUsername(),
                saved.getFullName(),
                saved.getRole().name(),
                jwtService.getExpirationMinutes()));
    }

    /**
     * Обновление ФИО текущего авторизованного пользователя.
     *
     * @param request        новое ФИО
     * @param authentication текущая аутентификация
     * @return обновлённый JWT и данные пользователя
     */
    @RequestMapping(path = "/profile", method = {RequestMethod.PUT, RequestMethod.PATCH})
    @PreAuthorize("isAuthenticated()")
    @Transactional
    @Operation(summary = "Обновление ФИО текущего пользователя")
    public ResponseEntity<AuthDtos.LoginResponse> updateProfile(
            @Valid @RequestBody AuthDtos.UpdateProfileRequest request,
            Authentication authentication) {
        if (authentication == null) {
            throw new org.springframework.security.authentication.BadCredentialsException(
                    "Требуется авторизация");
        }
        String fullName = request.fullName().trim();
        if (fullName.length() < 2) {
            throw new IllegalArgumentException("ФИО должно содержать от 2 до 120 символов");
        }
        User user = requireUser(authentication.getName());
        user.setFullName(fullName);
        User saved = userRepository.save(user);
        String token = jwtService.generateToken(saved);
        return ResponseEntity.ok(new AuthDtos.LoginResponse(
                token,
                "Bearer",
                saved.getUsername(),
                saved.getFullName(),
                saved.getRole().name(),
                jwtService.getExpirationMinutes()));
    }

    /**
     * Роли из текущей аутентификации (для диагностики клиента).
     *
     * @param authentication текущая аутентификация
     * @return список полномочий
     */
    @GetMapping("/authorities")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Полномочия текущего пользователя")
    public List<String> authorities(Authentication authentication) {
        return authentication == null
                ? List.of()
                : authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .toList();
    }

    private User requireUser(String username) {
        return userRepository.findByUsername(username).orElseThrow(() ->
                new java.util.NoSuchElementException("Пользователь " + username + " не найден"));
    }
}
