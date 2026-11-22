package ru.bcmanager.domain.premises;

import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import ru.bcmanager.domain.contracts.Exportable;
import ru.bcmanager.domain.contracts.RentCalculable;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;

/**
 * Абстрактная сущность коммерческого помещения (Plan.md, раздел 3.2).
 *
 * <p>Используется стратегия наследования {@code SINGLE_TABLE} с
 * дискриминатором {@code premises_type}: все четыре типа помещений хранятся в
 * одной таблице {@code premises}. Общие поля и поведение вынесены в этот
 * базовый класс, а полиморфный расчёт стоимости реализуют наследники
 * ({@link OfficeSpace}, {@link RetailSpace}, {@link ConferenceRoom},
 * {@link ParkingSpot}).
 *
 * <p>Класс реализует {@link RentCalculable} (расчёт ставки и OpEx) и
 * {@link Exportable} (выгрузка в CSV). Все денежные величины — {@link BigDecimal}
 * с масштабом 2.
 */
@Entity
@Table(name = "premises")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "premises_type", discriminatorType = DiscriminatorType.STRING, length = 20)
public abstract class Premises implements RentCalculable, Exportable {

    /** Точность денежных величин (копейки). */
    protected static final int MONEY_SCALE = 2;

    /** Округление денежных величин. */
    protected static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "building", nullable = false, length = 50)
    private String building;

    @Column(name = "floor", nullable = false)
    private Integer floor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PremisesStatus status;

    @Column(name = "area_sqm", nullable = false, precision = 8, scale = 2)
    private BigDecimal areaSqm;

    @Column(name = "base_rate_per_sqm", nullable = false, precision = 10, scale = 2)
    private BigDecimal baseRatePerSqm;

    @Column(name = "has_furniture", nullable = false)
    private boolean hasFurniture;

    @Column(name = "has_showcase", nullable = false)
    private boolean hasShowcase;

    @Column(name = "capacity_people")
    private Integer capacityPeople;

    @Column(name = "is_covered", nullable = false)
    private boolean covered;

    @Column(name = "description")
    private String description;

    /**
     * Тип помещения. Колонка {@code premises_type} является дискриминатором
     * наследования: значение всегда определяется конкретным подклассом,
     * поэтому поле доступно только на чтение.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "premises_type", nullable = false, insertable = false, updatable = false, length = 20)
    private PremisesType premisesType;

    /** Требуется JPA. */
    protected Premises() {
    }

    /**
     * Создать помещение с общими полями.
     *
     * @param code           код помещения, напр. {@code A-301}
     * @param building       корпус, напр. {@code Корпус А}
     * @param floor          этаж (может быть отрицательным для паркинга)
     * @param areaSqm        площадь в квадратных метрах
     * @param baseRatePerSqm базовая месячная ставка за 1 м²
     * @param status         статус доступности
     */
    protected Premises(
            String code,
            String building,
            Integer floor,
            BigDecimal areaSqm,
            BigDecimal baseRatePerSqm,
            PremisesStatus status) {
        this.code = Objects.requireNonNull(code, "code");
        this.building = Objects.requireNonNull(building, "building");
        this.floor = Objects.requireNonNull(floor, "floor");
        this.areaSqm = Objects.requireNonNull(areaSqm, "areaSqm");
        this.baseRatePerSqm = Objects.requireNonNull(baseRatePerSqm, "baseRatePerSqm");
        this.status = Objects.requireNonNull(status, "status");
    }

    // --------------------------------------------------------------------- //
    // Полиморфные точки расширения
    // --------------------------------------------------------------------- //

    /**
     * Тип помещения (дискриминатор наследования). После загрузки из БД значение
     * проставляется Hibernate; для новых (ещё не сохранённых) экземпляров
     * берётся из подкласса.
     *
     * @return конкретный тип помещения
     */
    public PremisesType getPremisesType() {
        return premisesType != null ? premisesType : resolvePremisesType();
    }

    /**
     * Тип помещения, определённый классом наследника. Используется в
     * {@link #toCsvRow()} и не зависит от состояния персистентности.
     *
     * @return конкретный тип помещения
     */
    public abstract PremisesType resolvePremisesType();

    /**
     * Коэффициент типа, применяемый к базовой ставке.
     *
     * @return множитель, напр. {@code 1.25} для торговых площадей
     */
    public abstract BigDecimal getTypeFactor();

    /**
     * Ставка эксплуатационного сбора в рублях за 1 м² в месяц.
     *
     * @return стоимость OpEx за квадратный метр
     */
    public abstract BigDecimal getOpexRatePerSqm();

    // --------------------------------------------------------------------- //
    // RentCalculable
    // --------------------------------------------------------------------- //

    /**
     * Базовая месячная стоимость: площадь × базовая ставка × коэффициент типа
     * плюс надбавка за особенность помещения.
     *
     * @return типизированная месячная ставка без OpEx и без НДС
     */
    @Override
    public BigDecimal calculateBaseMonthlyCost() {
        BigDecimal base = getAreaSqm().multiply(getBaseRatePerSqm());
        BigDecimal typed = base.multiply(getTypeFactor());
        return typed.add(typed.multiply(featureSurcharge())).setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Месячные эксплуатационные расходы.
     *
     * @return площадь × ставка OpEx за м²
     */
    @Override
    public BigDecimal calculateOpexCost() {
        return getAreaSqm()
                .multiply(getOpexRatePerSqm())
                .setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Надбавка к типизированной ставке за особенность помещения.
     *
     * <p>По умолчанию надбавки нет; типы с особенностью (мебель, витрина,
     * крытый паркинг) переопределяют этот метод.
     *
     * @return доля надбавки, напр. {@code 0.10} для +10%
     */
    protected BigDecimal featureSurcharge() {
        return BigDecimal.ZERO;
    }

    // --------------------------------------------------------------------- //
    // Exportable
    // --------------------------------------------------------------------- //

    /**
     * Строка CSV для выгрузки помещения.
     *
     * @return поля через {@code ';'}
     */
    @Override
    public String toCsvRow() {
        return String.join(
                ";",
                getCode(),
                getBuilding(),
                String.valueOf(getFloor()),
                resolvePremisesType().name(),
                getAreaSqm().toPlainString(),
                getBaseRatePerSqm().toPlainString(),
                getStatus().name(),
                getDescription() == null ? "" : getDescription());
    }

    // --------------------------------------------------------------------- //
    // Геттеры и сеттеры
    // --------------------------------------------------------------------- //

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getBuilding() {
        return building;
    }

    public void setBuilding(String building) {
        this.building = building;
    }

    public Integer getFloor() {
        return floor;
    }

    public void setFloor(Integer floor) {
        this.floor = floor;
    }

    public PremisesStatus getStatus() {
        return status;
    }

    public void setStatus(PremisesStatus status) {
        this.status = status;
    }

    public BigDecimal getAreaSqm() {
        return areaSqm;
    }

    public void setAreaSqm(BigDecimal areaSqm) {
        this.areaSqm = areaSqm;
    }

    public BigDecimal getBaseRatePerSqm() {
        return baseRatePerSqm;
    }

    public void setBaseRatePerSqm(BigDecimal baseRatePerSqm) {
        this.baseRatePerSqm = baseRatePerSqm;
    }

    public boolean isHasFurniture() {
        return hasFurniture;
    }

    public void setHasFurniture(boolean hasFurniture) {
        this.hasFurniture = hasFurniture;
    }

    public boolean isHasShowcase() {
        return hasShowcase;
    }

    public void setHasShowcase(boolean hasShowcase) {
        this.hasShowcase = hasShowcase;
    }

    public Integer getCapacityPeople() {
        return capacityPeople;
    }

    public void setCapacityPeople(Integer capacityPeople) {
        this.capacityPeople = capacityPeople;
    }

    public boolean isCovered() {
        return covered;
    }

    public void setCovered(boolean covered) {
        this.covered = covered;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Premises premises)) {
            return false;
        }
        return id != null && Objects.equals(id, premises.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "{id=" + id
                + ", code='" + code + '\''
                + ", floor=" + floor
                + ", areaSqm=" + areaSqm
                + ", status=" + status
                + '}';
    }
}
