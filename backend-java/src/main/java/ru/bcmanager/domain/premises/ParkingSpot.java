package ru.bcmanager.domain.premises;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;

/**
 * Парковочное место.
 *
 * <p>Правила расчёта (Plan.md, раздел 4.1, Алгоритм 3): коэффициент типа
 * {@code 1.0}; если место крытое — надбавка +12% к типизированной ставке;
 * эксплуатационный сбор 50 руб./м².
 */
@Entity
@DiscriminatorValue("PARKING")
public class ParkingSpot extends Premises {

    /** Коэффициент типа «паркинг». */
    public static final BigDecimal TYPE_FACTOR = new BigDecimal("1.00");

    /** Надбавка за крытое парковочное место. */
    public static final BigDecimal COVERED_SURCHARGE = new BigDecimal("0.12");

    /** Эксплуатационный сбор, руб./м² в месяц. */
    public static final BigDecimal OPEX_PER_SQM = new BigDecimal("50.00");

    /** Требуется JPA. */
    protected ParkingSpot() {
    }

    /**
     * Создать парковочное место.
     *
     * @param code           код места
     * @param building       корпус (обычно «Паркинг»)
     * @param floor          уровень (обычно {@code -1})
     * @param areaSqm        площадь, м²
     * @param baseRatePerSqm базовая ставка, руб./м²
     * @param status         статус
     * @param covered        признак крытого места
     */
    public ParkingSpot(
            String code,
            String building,
            Integer floor,
            BigDecimal areaSqm,
            BigDecimal baseRatePerSqm,
            PremisesStatus status,
            boolean covered) {
        super(code, building, floor, areaSqm, baseRatePerSqm, status);
        setCovered(covered);
    }

    @Override
    public PremisesType resolvePremisesType() {
        return PremisesType.PARKING;
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
        return isCovered() ? COVERED_SURCHARGE : BigDecimal.ZERO;
    }
}
