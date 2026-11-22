package ru.bcmanager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Ответ {@code 403 Forbidden} в едином формате ошибки API (Plan.md, 5.8).
 *
 * <p>Используется, когда пользователь аутентифицирован, но его роли
 * недостаточно для операции (например, {@code MANAGER} удаляет помещение).
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * Создать обработчик отказа в доступе.
     *
     * @param objectMapper сериализатор JSON
     */
    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        RestAuthenticationEntryPoint.write(
                response,
                objectMapper,
                HttpStatus.FORBIDDEN,
                "Недостаточно прав для выполнения операции");
    }
}
