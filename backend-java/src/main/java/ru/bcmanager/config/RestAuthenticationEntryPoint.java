package ru.bcmanager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import ru.bcmanager.exception.ApiError;

/**
 * Ответ {@code 401 Unauthorized} в едином формате ошибки API (Plan.md, 5.8).
 *
 * <p>Без собственной точки входа Spring Security по умолчанию отдаёт
 * {@code 403} даже для неавторизованных запросов. Здесь возвращается
 * корректный {@code 401} с телом {@link ApiError}.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * Создать точку входа.
     *
     * @param objectMapper сериализатор JSON
     */
    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        write(response, objectMapper, HttpStatus.UNAUTHORIZED, "Требуется авторизация");
    }

    /**
     * Записать тело ошибки в ответ.
     *
     * @param response     ответ
     * @param mapper       сериализатор JSON
     * @param status       HTTP-статус
     * @param message      сообщение
     * @throws IOException при ошибке записи
     */
    static void write(
            HttpServletResponse response,
            ObjectMapper mapper,
            HttpStatus status,
            String message) throws IOException {
        ApiError body = new ApiError(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), body);
    }
}
