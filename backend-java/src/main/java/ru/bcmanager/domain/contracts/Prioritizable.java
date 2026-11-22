package ru.bcmanager.domain.contracts;

import java.time.LocalDateTime;

/**
 * Контракт вычисления эффективного веса приоритета с учётом эскалации SLA
 * (Алгоритм 5, Plan.md раздел 4.1).
 */
public interface Prioritizable {

    /**
     * Эффективный вес приоритета заявки на момент {@code now}.
     *
     * <p>Правила эскалации: {@code MEDIUM}, ожидающая более 48 часов,
     * получает вес 2; {@code HIGH}, ожидающая более 24 часов, — вес 1.
     * Остальные приоритеты не эскалируются.
     *
     * @param now контрольный момент времени
     * @return вес от 1 (высший приоритет) до 4
     */
    int calculateEffectivePriorityWeight(LocalDateTime now);
}
