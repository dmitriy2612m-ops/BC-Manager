package ru.bcmanager.domain.enums;

/**
 * Тип коммерческого помещения. Значение используется как дискриминатор
 * наследования в колонке {@code premises_type}.
 */
public enum PremisesType {
    OFFICE,
    RETAIL,
    CONFERENCE,
    PARKING
}
