package ru.bcmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.bcmanager.domain.User;
import ru.bcmanager.dto.AuthDtos;
import ru.bcmanager.repository.UserRepository;
import ru.bcmanager.security.JwtService;

/**
 * Аутентификация по логину и паролю (Plan.md, раздел 5.1).
 *
 * <p>Контроллер только делегирует проверку учётных данных менеджеру
 * аутентификации и выпускает JWT; бизнес-логики в нём нет.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Аутентификация", description = "Вход в систему и данные текущего пользователя")
public class AuthController {

    private final AuthenticationManager authenticationManager;

    private final JwtService jwtService;

    private final UserRepository userRepository;

    /**
     * Создать контроллер аутентификации.
     *
     * @param authenticationManager менеджер проверки учётных данных
     * @param jwtService            сервис выпуска токенов
     * @param userRepository        репозиторий пользователей
     */
    public AuthController(
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            UserRepository userRepository) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
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
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.username(), request.password()));
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
