package ru.bcmanager.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Единый формат ошибки API (Plan.md, раздел 5.8).
 *
 * @param timestamp момент формирования ответа
 * @param status    HTTP-статус
 * @param error     краткое название статуса
 * @param message   человекочитаемое сообщение
 * @param details   детализация (например, ошибки валидации полей)
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiError(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        List<FieldViolation> details) {

    /**
     * Ошибка отдельного поля запроса.
     *
     * @param field   имя поля
     * @param message причина
     */
    public record FieldViolation(String field, String message) {
    }
}
