package ru.bcmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO аутентификации (Plan.md, раздел 5.1).
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /**
     * Запрос входа в систему.
     *
     * @param username логин
     * @param password пароль в открытом виде
     */
    public record LoginRequest(
            @NotBlank(message = "Логин обязателен")
            @Size(max = 50, message = "Логин не длиннее 50 символов")
            String username,

            @NotBlank(message = "Пароль обязателен")
            @Size(max = 100, message = "Пароль не длиннее 100 символов")
            String password) {
    }

    /**
     * Ответ на успешный вход.
     *
     * @param token       JWT для заголовка {@code Authorization}
     * @param tokenType   тип токена (всегда {@code Bearer})
     * @param username    логин
     * @param fullName    ФИО сотрудника
     * @param role        роль доступа
     * @param expiresInMinutes срок действия токена в минутах
     */
    public record LoginResponse(
            String token,
            String tokenType,
            String username,
            String fullName,
            String role,
            long expiresInMinutes) {
    }

    /**
     * Данные текущего авторизованного пользователя.
     *
     * @param username логин
     * @param fullName ФИО сотрудника
     * @param role     роль доступа
     */
    public record CurrentUserResponse(String username, String fullName, String role) {
    }
}
