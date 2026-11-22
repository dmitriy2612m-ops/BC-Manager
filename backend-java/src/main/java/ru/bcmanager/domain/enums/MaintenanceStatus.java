package ru.bcmanager.domain.enums;

/**
 * Статус заявки на техническое обслуживание.
 */
public enum MaintenanceStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CANCELLED;

    /**
     * Участвует ли заявка в активной приоритетной очереди.
     *
     * @return {@code true} для {@code OPEN} и {@code IN_PROGRESS}
     */
    public boolean isActive() {
        return this == OPEN || this == IN_PROGRESS;
    }
}
