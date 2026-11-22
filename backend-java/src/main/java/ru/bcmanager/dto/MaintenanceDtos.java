package ru.bcmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import ru.bcmanager.domain.enums.MaintenanceCategory;
import ru.bcmanager.domain.enums.MaintenancePriority;
import ru.bcmanager.domain.enums.MaintenanceStatus;

/**
 * DTO заявок на техобслуживание (Plan.md, раздел 5.6).
 */
public final class MaintenanceDtos {

    private MaintenanceDtos() {
    }

    /**
     * Запрос создания заявки.
     *
     * @param premisesId помещение
     * @param tenantId   инициатор (может быть {@code null})
     * @param title      краткая суть проблемы
     * @param description подробное описание
     * @param category   категория
     * @param priority   приоритет
     */
    public record CreateMaintenanceRequest(
            @NotNull(message = "Помещение обязательно")
            Long premisesId,

            Long tenantId,

            @NotBlank(message = "Тема заявки обязательна")
            @Size(max = 150, message = "Тема не длиннее 150 символов")
            String title,

            @NotBlank(message = "Описание обязательно")
            @Size(max = 4000, message = "Описание не длиннее 4000 символов")
            String description,

            @NotNull(message = "Категория обязательна")
            MaintenanceCategory category,

            @NotNull(message = "Приоритет обязателен")
            MaintenancePriority priority) {
    }

    /**
     * Запрос смены статуса заявки.
     *
     * @param status новый статус
     */
    public record ChangeStatusRequest(
            @NotNull(message = "Статус обязателен")
            MaintenanceStatus status) {
    }

    /**
     * Карточка заявки для выдачи.
     *
     * @param id                идентификатор
     * @param premisesId        помещение
     * @param premisesCode      код помещения
     * @param tenantId          инициатор
     * @param tenantName        наименование арендатора
     * @param title             тема
     * @param description       описание
     * @param category          категория
     * @param priority          приоритет
     * @param status            статус
     * @param createdAt         время поступления
     * @param resolvedAt        время закрытия
     * @param queuePosition     позиция в приоритетной очереди (0 — вне очереди)
     * @param effectivePriority эффективный вес с учётом SLA
     * @param escalated         сработала ли SLA-эскалация
     */
    public record MaintenanceResponse(
            Long id,
            Long premisesId,
            String premisesCode,
            Long tenantId,
            String tenantName,
            String title,
            String description,
            MaintenanceCategory category,
            MaintenancePriority priority,
            MaintenanceStatus status,
            LocalDateTime createdAt,
            LocalDateTime resolvedAt,
            int queuePosition,
            int effectivePriority,
            boolean escalated) {
    }
}
