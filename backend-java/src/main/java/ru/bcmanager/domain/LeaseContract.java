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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import ru.bcmanager.domain.enums.ContractStatus;
import ru.bcmanager.domain.premises.Premises;

/**
 * Договор аренды (Plan.md, раздел 3.4).
 *
 * <p>Связывает помещение и арендатора на интервал дат. Месячная плата уже
 * включает скидку и НДС (см. Алгоритм 3).
 */
@Entity
@Table(name = "lease_contracts")
public class LeaseContract {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(name = "contract_number", nullable = false, length = 40)
    private String contractNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "premises_id", nullable = false)
    private Premises premises;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "monthly_rent", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyRent;

    @Column(name = "discount_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercent = BigDecimal.ZERO;

    @Column(name = "deposit_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContractStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Требуется JPA. */
    protected LeaseContract() {
    }

    /**
     * Создать договор аренды.
     *
     * @param contractNumber номер договора, напр. {@code BC-2026-0001}
     * @param premises       арендуемое помещение
     * @param tenant         арендатор
     * @param startDate      дата начала аренды
     * @param endDate        дата окончания аренды
     * @param monthlyRent    месячная плата с НДС и скидкой
     * @param status         статус договора
     */
    public LeaseContract(
            String contractNumber,
            Premises premises,
            Tenant tenant,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal monthlyRent,
            ContractStatus status) {
        this.contractNumber = Objects.requireNonNull(contractNumber, "contractNumber");
        this.premises = Objects.requireNonNull(premises, "premises");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        this.endDate = Objects.requireNonNull(endDate, "endDate");
        this.monthlyRent = Objects.requireNonNull(monthlyRent, "monthlyRent");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Участвует ли договор в проверке конфликтов дат (Алгоритм 1).
     *
     * @return {@code true} для {@code ACTIVE} и {@code DRAFT}
     */
    public boolean isConflictRelevant() {
        return status == ContractStatus.ACTIVE || status == ContractStatus.DRAFT;
    }

    /**
     * Пересекается ли интервал договора с заданным (включительно).
     *
     * @param otherStart начало проверяемого интервала
     * @param otherEnd   конец проверяемого интервала
     * @return {@code true} при пересечении хотя бы на один день
     */
    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return !startDate.isAfter(otherEnd) && !otherStart.isAfter(endDate);
    }

    /**
     * Действует ли договор на указанную дату.
     *
     * @param date проверяемая дата
     * @return {@code true}, если {@code startDate <= date <= endDate}
     */
    public boolean coversDate(LocalDate date) {
        return !startDate.isAfter(date) && !date.isAfter(endDate);
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

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public String getContractNumber() {
        return contractNumber;
    }

    public void setContractNumber(String contractNumber) {
        this.contractNumber = contractNumber;
    }

    public Premises getPremises() {
        return premises;
    }

    public void setPremises(Premises premises) {
        this.premises = premises;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getMonthlyRent() {
        return monthlyRent;
    }

    public void setMonthlyRent(BigDecimal monthlyRent) {
        this.monthlyRent = monthlyRent;
    }

    public BigDecimal getDiscountPercent() {
        return discountPercent;
    }

    public void setDiscountPercent(BigDecimal discountPercent) {
        this.discountPercent = discountPercent;
    }

    public BigDecimal getDepositAmount() {
        return depositAmount;
    }

    public void setDepositAmount(BigDecimal depositAmount) {
        this.depositAmount = depositAmount;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public void setStatus(ContractStatus status) {
        this.status = status;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LeaseContract contract)) {
            return false;
        }
        return id != null && Objects.equals(id, contract.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "LeaseContract{id=" + id
                + ", contractNumber='" + contractNumber + '\''
                + ", startDate=" + startDate
                + ", endDate=" + endDate
                + ", status=" + status
                + '}';
    }
}
