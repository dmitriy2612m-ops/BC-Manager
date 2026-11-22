package ru.bcmanager.domain.contracts;

import java.math.BigDecimal;

/**
 * Контракт полиморфного расчёта стоимости аренды помещения
 * (Plan.md, раздел 4.3).
 *
 * <p>Каждый тип помещения реализует собственную формулу базовой ставки
 * (коэффициент типа и надбавка за особенность), а эксплуатационный сбор
 * зависит от типа помещения.
 */
public interface RentCalculable {

    /**
     * Базовая месячная стоимость с учётом коэффициента типа и надбавки
     * за особенность помещения ({@code base_rate_per_sqm * area} и модификаторы).
     *
     * @return месячная типизированная ставка без OpEx и без НДС
     */
    BigDecimal calculateBaseMonthlyCost();

    /**
     * Месячные эксплуатационные расходы ({@code opex_per_sqm * area}).
     *
     * @return сумма эксплуатационного сбора без НДС
     */
    BigDecimal calculateOpexCost();
}
