package ru.bcmanager.domain.premises;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;

/**
 * Торговая площадь.
 *
 * <p>Правила расчёта (Plan.md, раздел 4.1, Алгоритм 3): коэффициент типа
 * {@code 1.25}; при наличии витрины надбавка +15% к типизированной ставке;
 * эксплуатационный сбор 200 руб./м².
 */
@Entity
@DiscriminatorValue("RETAIL")
public class RetailSpace extends Premises {

    /** Коэффициент типа «торговая площадь». */
    public static final BigDecimal TYPE_FACTOR = new BigDecimal("1.25");

    /** Надбавка за витрину / отдельный вход. */
    public static final BigDecimal SHOWCASE_SURCHARGE = new BigDecimal("0.15");

    /** Эксплуатационный сбор, руб./м² в месяц. */
    public static final BigDecimal OPEX_PER_SQM = new BigDecimal("200.00");

    /** Требуется JPA. */
    protected RetailSpace() {
    }

    /**
     * Создать торговую площадь.
     *
     * @param code           код помещения
     * @param building       корпус
     * @param floor          этаж
     * @param areaSqm        площадь, м²
     * @param baseRatePerSqm базовая ставка, руб./м²
     * @param status         статус
     * @param hasShowcase    наличие витрины / отдельного входа
     */
    public RetailSpace(
            String code,
            String building,
            Integer floor,
            BigDecimal areaSqm,
            BigDecimal baseRatePerSqm,
            PremisesStatus status,
            boolean hasShowcase) {
        super(code, building, floor, areaSqm, baseRatePerSqm, status);
        setHasShowcase(hasShowcase);
    }

    @Override
    public PremisesType resolvePremisesType() {
        return PremisesType.RETAIL;
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
        return isHasShowcase() ? SHOWCASE_SURCHARGE : BigDecimal.ZERO;
    }
}
