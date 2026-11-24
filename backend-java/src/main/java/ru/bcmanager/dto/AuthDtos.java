package ru.bcmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
     * Запрос регистрации нового пользователя (логин, пароль и ФИО, без почты).
     *
     * @param username логин (3–50 символов)
     * @param password пароль (4–100 символов)
     * @param fullName ФИО пользователя (необязательно в API; если не задано — подставляется логин)
     */
    public record RegisterRequest(
            @NotBlank(message = "Логин обязателен")
            @Size(min = 3, max = 50, message = "Логин должен содержать от 3 до 50 символов")
            @Pattern(
                    regexp = "^[\\p{L}\\p{N}_.-]+$",
                    message = "Логин может содержать буквы, цифры, точки, дефисы и подчёркивания")
            String username,

            @NotBlank(message = "Пароль обязателен")
            @Size(min = 4, max = 100, message = "Пароль должен содержать от 4 до 100 символов")
            String password,

            @Size(max = 120, message = "ФИО не длиннее 120 символов")
            String fullName) {
    }

    /**
     * Запрос обновления ФИО текущего пользователя.
     *
     * @param fullName новое ФИО (2–120 символов)
     */
    public record UpdateProfileRequest(
            @NotBlank(message = "Укажите ФИО")
            @Size(min = 2, max = 120, message = "ФИО должно содержать от 2 до 120 символов")
            String fullName) {
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
     * Запрос смены пароля текущего авторизованного пользователя.
     *
     * @param currentPassword текущий пароль
     * @param newPassword     новый пароль (4–100 символов)
     */
    public record ChangePasswordRequest(
            @NotBlank(message = "Текущий пароль обязателен")
            @Size(max = 100, message = "Текущий пароль не длиннее 100 символов")
            String currentPassword,

            @NotBlank(message = "Новый пароль обязателен")
            @Size(min = 4, max = 100, message = "Новый пароль должен содержать от 4 до 100 символов")
            String newPassword) {
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
