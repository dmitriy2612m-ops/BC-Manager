package ru.bcmanager.domain.contracts;

/**
 * Контракт экспорта сущности в строку CSV
 * (Plan.md, раздел 4.3). Используется для выгрузки
 * {@code GET /api/v1/analytics/export/csv}.
 */
public interface Exportable {

    /**
     * Сериализовать сущность в одну строку CSV без завершающего перевода строки.
     *
     * <p>Поля разделяются {@code ';'}, значения с разделителями
     * экранируются кавычками.
     *
     * @return строка CSV
     */
    String toCsvRow();
}
