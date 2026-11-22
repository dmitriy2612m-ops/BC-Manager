package ru.bcmanager.domain.enums;

/**
 * Приоритет заявки на ТО с базовым весом для приоритетной очереди
 * (Алгоритм 5, Plan.md раздел 4.1).
 */
public enum MaintenancePriority {
    /** Наивысший приоритет: вес 1. */
    CRITICAL(1),
    /** Высокий приоритет: вес 2. */
    HIGH(2),
    /** Средний приоритет: вес 3. */
    MEDIUM(3),
    /** Низкий приоритет: вес 4. */
    LOW(4);

    private final int baseWeight;

    MaintenancePriority(int baseWeight) {
        this.baseWeight = baseWeight;
    }

    /**
     * Базовый вес приоритета (без эскалации по SLA).
     *
     * @return вес от 1 (CRITICAL) до 4 (LOW)
     */
    public int getBaseWeight() {
        return baseWeight;
    }
}
