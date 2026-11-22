package ru.bcmanager.domain.premises;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;

/**
 * Офисное помещение.
 *
 * <p>Правила расчёта (Plan.md, раздел 4.1, Алгоритм 3): коэффициент типа
 * {@code 1.0}; при наличии мебели надбавка +10% к типизированной ставке;
 * эксплуатационный сбор 150 руб./м².
 */
@Entity
@DiscriminatorValue("OFFICE")
public class OfficeSpace extends Premises {

    /** Коэффициент типа «офис». */
    public static final BigDecimal TYPE_FACTOR = new BigDecimal("1.00");

    /** Надбавка за наличие мебели. */
    public static final BigDecimal FURNITURE_SURCHARGE = new BigDecimal("0.10");

    /** Эксплуатационный сбор, руб./м² в месяц. */
    public static final BigDecimal OPEX_PER_SQM = new BigDecimal("150.00");

    /** Требуется JPA. */
    protected OfficeSpace() {
    }

    /**
     * Создать офисное помещение.
     *
     * @param code           код помещения
     * @param building       корпус
     * @param floor          этаж
     * @param areaSqm        площадь, м²
     * @param baseRatePerSqm базовая ставка, руб./м²
     * @param status         статус
     * @param hasFurniture   наличие мебели
     */
    public OfficeSpace(
            String code,
            String building,
            Integer floor,
            BigDecimal areaSqm,
            BigDecimal baseRatePerSqm,
            PremisesStatus status,
            boolean hasFurniture) {
        super(code, building, floor, areaSqm, baseRatePerSqm, status);
        setHasFurniture(hasFurniture);
    }

    @Override
    public PremisesType resolvePremisesType() {
        return PremisesType.OFFICE;
    }

    @Override
    public BigDecimal getTypeFactor() {
        return TYPE_FACTOR;
    }

    @Override
    public BigDecimal getOpexRatePerSqm() {
        return OPEX_PER_SQM;
    }

    @Override
    protected BigDecimal featureSurcharge() {
        return isHasFurniture() ? FURNITURE_SURCHARGE : BigDecimal.ZERO;
    }
}
