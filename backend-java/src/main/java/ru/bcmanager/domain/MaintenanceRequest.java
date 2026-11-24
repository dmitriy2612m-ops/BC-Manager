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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import ru.bcmanager.domain.contracts.Prioritizable;
import ru.bcmanager.domain.enums.MaintenanceCategory;
import ru.bcmanager.domain.enums.MaintenancePriority;
import ru.bcmanager.domain.enums.MaintenanceStatus;
import ru.bcmanager.domain.premises.Premises;

/**
 * Заявка на техническое обслуживание (Plan.md, раздел 3.6).
 *
 * <p>Реализует {@link Prioritizable}: базовый вес приоритета повышается при
 * превышении SLA ожидания (Алгоритм 5).
 */
@Entity
@Table(name = "maintenance_requests")
public class MaintenanceRequest implements Prioritizable {

    /** Порог ожидания (часов) до эскалации {@code MEDIUM}. */
    public static final long MEDIUM_SLA_HOURS = 48L;

    /** Порог ожидания (часов) до эскалации {@code HIGH}. */
    public static final long HIGH_SLA_HOURS = 24L;

    /** Эффективный вес {@code MEDIUM} после эскалации. */
    public static final int MEDIUM_ESCALATED_WEIGHT = 2;

    /** Эффективный вес {@code HIGH} после эскалации. */
    public static final int HIGH_ESCALATED_WEIGHT = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "premises_id", nullable = false)
    private Premises premises;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "description", nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private MaintenanceCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private MaintenancePriority priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MaintenanceStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** Требуется JPA. */
    protected MaintenanceRequest() {
    }

    /**
     * Создать открытую заявку на ТО.
     *
     * @param premises    помещение
     * @param tenant      инициатор (может быть {@code null})
     * @param title       краткая суть проблемы
     * @param description подробное описание
     * @param category    категория
     * @param priority    приоритет
     */
    public MaintenanceRequest(
            Premises premises,
            Tenant tenant,
            String title,
            String description,
            MaintenanceCategory category,
            MaintenancePriority priority) {
        this.premises = Objects.requireNonNull(premises, "premises");
        this.tenant = tenant;
        this.title = Objects.requireNonNull(title, "title");
        this.description = Objects.requireNonNull(description, "description");
        this.category = Objects.requireNonNull(category, "category");
        this.priority = Objects.requireNonNull(priority, "priority");
        this.status = MaintenanceStatus.OPEN;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Эффективный вес приоритета с эскалацией SLA.
     *
     * <p>Эскалация срабатывает только при <em>строгом</em> превышении порога:
     * ровно 48 часов для {@code MEDIUM} и ровно 24 часа для {@code HIGH} ещё
     * не поднимают вес.
     *
     * @param now контрольный момент времени
     * @return вес от 1 до 4
     */
    @Override
    public int calculateEffectivePriorityWeight(LocalDateTime now) {
        int base = priority.getBaseWeight();
        long waitingHours = Duration.between(createdAt, now).toHours();
        if (priority == MaintenancePriority.MEDIUM && waitingHours > MEDIUM_SLA_HOURS) {
            return MEDIUM_ESCALATED_WEIGHT;
        }
        if (priority == MaintenancePriority.HIGH && waitingHours > HIGH_SLA_HOURS) {
            return HIGH_ESCALATED_WEIGHT;
        }
        return base;
    }

    /**
     * Эскалирована ли заявка (эффективный вес ниже базового).
     *
     * @param now контрольный момент времени
     * @return {@code true}, если сработала SLA-эскалация
     */
    public boolean isEscalated(LocalDateTime now) {
        return calculateEffectivePriorityWeight(now) < priority.getBaseWeight();
    }

    /**
     * Участвует ли заявка в приоритетной очереди.
     *
     * @return {@code true} для {@code OPEN} и {@code IN_PROGRESS}
     */
    public boolean isActive() {
        return status.isActive();
    }

    /**
     * Перевести заявку в статус {@code RESOLVED} с фиксацией времени закрытия.
     *
     * @param resolvedAt момент закрытия
     */
    public void resolve(LocalDateTime resolvedAt) {
        this.status = MaintenanceStatus.RESOLVED;
        this.resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public MaintenanceCategory getCategory() {
        return category;
    }

    public void setCategory(MaintenanceCategory category) {
        this.category = category;
    }

    public MaintenancePriority getPriority() {
        return priority;
    }

    public void setPriority(MaintenancePriority priority) {
        this.priority = priority;
    }

    public MaintenanceStatus getStatus() {
        return status;
    }

    public void setStatus(MaintenanceStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MaintenanceRequest request)) {
            return false;
        }
        return id != null && Objects.equals(id, request.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "MaintenanceRequest{id=" + id
                + ", priority=" + priority
                + ", status=" + status
                + ", createdAt=" + createdAt
                + '}';
    }
}
