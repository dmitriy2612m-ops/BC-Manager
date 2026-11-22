package ru.bcmanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import ru.bcmanager.domain.enums.PaymentStatus;

/**
 * Запись графика платежей (Plan.md, раздел 3.5).
 *
 * <p>Один платёж соответствует одному расчётному периоду договора. Хранит
 * начисление, фактическую оплату и начисленную пеню.
 */
@Entity
@Table(name = "payments")
public class Payment {

    /** Точность денежных величин (копейки). */
    public static final int MONEY_SCALE = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contract_id", nullable = false)
    private LeaseContract contract;

    @Column(name = "period_number", nullable = false)
    private Integer periodNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "amount_due", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountDue;

    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(name = "paid_date")
    private LocalDate paidDate;

    @Column(name = "penalty_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal penaltyAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    /** Требуется JPA. */
    protected Payment() {
    }

    /**
     * Создать запись графика платежей.
     *
     * @param contract     договор аренды
     * @param periodNumber номер расчётного периода (с 1)
     * @param dueDate      крайняя дата оплаты
     * @param amountDue    сумма к оплате
     */
    public Payment(LeaseContract contract, Integer periodNumber, LocalDate dueDate, BigDecimal amountDue) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.periodNumber = Objects.requireNonNull(periodNumber, "periodNumber");
        this.dueDate = Objects.requireNonNull(dueDate, "dueDate");
        this.amountDue = Objects.requireNonNull(amountDue, "amountDue");
        this.paidAmount = BigDecimal.ZERO;
        this.penaltyAmount = BigDecimal.ZERO;
        this.status = PaymentStatus.PENDING;
    }

    /**
     * Непогашенный остаток по платежу.
     *
     * @return {@code amountDue - paidAmount}, не меньше нуля
     */
    public BigDecimal outstandingDebt() {
        BigDecimal debt = amountDue.subtract(paidAmount);
        return debt.signum() < 0 ? BigDecimal.ZERO : debt.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Просрочен ли платёж на указанную дату.
     *
     * @param calculationDate контрольная дата
     * @return {@code true}, если платёж не оплачен и срок оплаты уже прошёл
     */
    public boolean isOverdueOn(LocalDate calculationDate) {
        return status != PaymentStatus.PAID && dueDate.isBefore(calculationDate);
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

    public LeaseContract getContract() {
        return contract;
    }

    public void setContract(LeaseContract contract) {
        this.contract = contract;
    }

    public Integer getPeriodNumber() {
        return periodNumber;
    }

    public void setPeriodNumber(Integer periodNumber) {
        this.periodNumber = periodNumber;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public BigDecimal getAmountDue() {
        return amountDue;
    }

    public void setAmountDue(BigDecimal amountDue) {
        this.amountDue = amountDue;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public LocalDate getPaidDate() {
        return paidDate;
    }

    public void setPaidDate(LocalDate paidDate) {
        this.paidDate = paidDate;
    }

    public BigDecimal getPenaltyAmount() {
        return penaltyAmount;
    }

    public void setPenaltyAmount(BigDecimal penaltyAmount) {
        this.penaltyAmount = penaltyAmount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Payment payment)) {
            return false;
        }
        return id != null && Objects.equals(id, payment.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Payment{id=" + id
                + ", period=" + periodNumber
                + ", dueDate=" + dueDate
                + ", amountDue=" + amountDue
                + ", status=" + status
                + '}';
    }
}
