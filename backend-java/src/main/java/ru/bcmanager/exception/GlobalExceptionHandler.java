package ru.bcmanager.exception;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;

/**
 * Глобальная обработка ошибок REST API (Plan.md, раздел 5.8).
 *
 * <p>Все исключения приводятся к единому формату {@link ApiError}:
 * {@code timestamp}, {@code status}, {@code error}, {@code message},
 * {@code details}. Конфликт дат аренды отдаётся как {@code 409 Conflict},
 * нарушение прав — {@code 403 Forbidden}, некорректный ввод — {@code 400}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Пересечение дат аренды, дубль кода/ИНН и другие конфликты состояния.
     *
     * @param exception исключение сервиса
     * @return ответ {@code 409 Conflict}
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalStateException exception) {
        return build(HttpStatus.CONFLICT, exception.getMessage(), List.of());
    }

    /**
     * Некорректные бизнес-данные (например, занятый номер договора).
     *
     * @param exception исключение сервиса
     * @return ответ {@code 400 Bad Request}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException exception) {
        return build(HttpStatus.BAD_REQUEST, exception.getMessage(), List.of());
    }

    /**
     * Ошибки валидации DTO ({@code @NotNull}, {@code @Positive}, {@code @Pattern}).
     *
     * @param exception исключение Spring
     * @return ответ {@code 400 Bad Request} с детализацией по полям
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        List<ApiError.FieldViolation> details = new ArrayList<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> details.add(
                new ApiError.FieldViolation(error.getField(), error.getDefaultMessage())));
        exception.getBindingResult().getGlobalErrors().forEach(error -> details.add(
                new ApiError.FieldViolation(error.getObjectName(), error.getDefaultMessage())));
        return build(HttpStatus.BAD_REQUEST, "Ошибка валидации запроса", details);
    }

    /**
     * Неразбираемое тело запроса (некорректный JSON или дата).
     *
     * @param exception исключение разбора
     * @return ответ {@code 400 Bad Request}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException exception) {
        return build(
                HttpStatus.BAD_REQUEST,
                "Некорректный формат тела запроса",
                List.of());
    }

    /**
     * Сущность не найдена.
     *
     * @param exception исключение сервиса
     * @return ответ {@code 404 Not Found}
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException exception) {
        return build(HttpStatus.NOT_FOUND, exception.getMessage(), List.of());
    }

    /**
     * Неверный логин или пароль.
     *
     * @param exception исключение аутентификации
     * @return ответ {@code 401 Unauthorized}
     */
    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ApiError> handleUnauthorized(AuthenticationException exception) {
        return build(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль", List.of());
    }

    /**
     * Недостаточно прав для операции.
     *
     * @param exception исключение доступа
     * @return ответ {@code 403 Forbidden}
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleForbidden(AccessDeniedException exception) {
        return build(
                HttpStatus.FORBIDDEN,
                "Недостаточно прав для выполнения операции",
                List.of());
    }

    /**
     * Нарушение ограничений БД (уникальность, внешний ключ).
     *
     * @param exception исключение доступа к данным
     * @return ответ {@code 409 Conflict}
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException exception) {
        return build(
                HttpStatus.CONFLICT,
                "Операция нарушает целостность данных",
                List.of());
    }

    /**
     * Неподдерживаемый HTTP-метод.
     *
     * @param exception исключение Spring MVC
     * @return ответ {@code 405 Method Not Allowed}
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, exception.getMessage(), List.of());
    }

    /**
     * Непредвиденная ошибка.
     *
     * @param exception исключение
     * @return ответ {@code 500 Internal Server Error}
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException exception) {
        return build(HttpStatus.NOT_FOUND, "Ресурс не найден", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        LOG.error("Необработанная ошибка REST API", exception);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Внутренняя ошибка сервера",
                List.of());
    }

    private static ResponseEntity<ApiError> build(
            HttpStatus status, String message, List<ApiError.FieldViolation> details) {
        ApiError body = new ApiError(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message == null ? status.getReasonPhrase() : message,
                details);
        return ResponseEntity.status(status).body(body);
    }
}
