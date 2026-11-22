package ru.bcmanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Арендатор — юридическое лицо или ИП (Plan.md, раздел 3.3).
 */
@Entity
@Table(name = "tenants")
public class Tenant {

    /** Длина ИНН юридического лица. */
    public static final int INN_LENGTH_LEGAL = 10;

    /** Длина ИНН индивидуального предпринимателя. */
    public static final int INN_LENGTH_INDIVIDUAL = 12;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "inn", nullable = false, unique = true, length = 12)
    private String inn;

    @Column(name = "contact_person", nullable = false, length = 120)
    private String contactPerson;

    @Column(name = "phone", nullable = false, length = 30)
    private String phone;

    @Column(name = "email", nullable = false, length = 120)
    private String email;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Требуется JPA. */
    protected Tenant() {
    }

    /**
     * Создать активного арендатора.
     *
     * @param companyName   наименование организации / ИП
     * @param inn           ИНН: 10 или 12 цифр
     * @param contactPerson контактное лицо
     * @param phone         телефон
     * @param email         электронная почта
     */
    public Tenant(String companyName, String inn, String contactPerson, String phone, String email) {
        this.companyName = Objects.requireNonNull(companyName, "companyName");
        this.inn = Objects.requireNonNull(inn, "inn");
        this.contactPerson = Objects.requireNonNull(contactPerson, "contactPerson");
        this.phone = Objects.requireNonNull(phone, "phone");
        this.email = Objects.requireNonNull(email, "email");
        this.active = true;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Проверить корректность ИНН (10 или 12 цифр).
     *
     * @param value проверяемое значение
     * @return {@code true}, если значение состоит из ровно 10 или 12 цифр
     */
    public static boolean isValidInn(String value) {
        if (value == null) {
            return false;
        }
        int length = value.length();
        if (length != INN_LENGTH_LEGAL && length != INN_LENGTH_INDIVIDUAL) {
            return false;
        }
        for (int index = 0; index < length; index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Деактивировать контрагента (мягкое удаление).
     */
    public void deactivate() {
        this.active = false;
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

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getInn() {
        return inn;
    }

    public void setInn(String inn) {
        this.inn = inn;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public void setContactPerson(String contactPerson) {
        this.contactPerson = contactPerson;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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
        if (!(other instanceof Tenant tenant)) {
            return false;
        }
        return id != null && Objects.equals(id, tenant.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "Tenant{id=" + id + ", companyName='" + companyName + "', inn='" + inn + "'}";
    }
}
