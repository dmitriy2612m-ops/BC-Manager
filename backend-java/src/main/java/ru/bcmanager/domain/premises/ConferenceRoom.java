package ru.bcmanager.domain.premises;

import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;

/**
 * Переговорная / конференц-зал.
 *
 * <p>Правила расчёта (Plan.md, раздел 4.1, Алгоритм 3): коэффициент типа
 * {@code 1.15}; надбавок за особенности нет; эксплуатационный сбор
 * 100 руб./м². Дополнительно хранится вместимость в человека.
 */
@Entity
@DiscriminatorValue("CONFERENCE")
public class ConferenceRoom extends Premises {

    /** Коэффициент типа «переговорная». */
    public static final BigDecimal TYPE_FACTOR = new BigDecimal("1.15");

    /** Эксплуатационный сбор, руб./м² в месяц. */
    public static final BigDecimal OPEX_PER_SQM = new BigDecimal("100.00");

    /** Требуется JPA. */
    protected ConferenceRoom() {
    }

    /**
     * Создать переговорную.
     *
     * @param code           код помещения
     * @param building       корпус
     * @param floor          этаж
     * @param areaSqm        площадь, м²
     * @param baseRatePerSqm базовая ставка, руб./м²
     * @param status         статус
     * @param capacityPeople вместимость в человек
     */
    public ConferenceRoom(
            String code,
            String building,
            Integer floor,
            BigDecimal areaSqm,
            BigDecimal baseRatePerSqm,
            PremisesStatus status,
            Integer capacityPeople) {
        super(code, building, floor, areaSqm, baseRatePerSqm, status);
        setCapacityPeople(capacityPeople);
    }

    @Override
    public PremisesType resolvePremisesType() {
        return PremisesType.CONFERENCE;
    }

    @Override
    public BigDecimal getTypeFactor() {
        return TYPE_FACTOR;
    }

    @Override
    public BigDecimal getOpexRatePerSqm() {
        return OPEX_PER_SQM;
    }
}
