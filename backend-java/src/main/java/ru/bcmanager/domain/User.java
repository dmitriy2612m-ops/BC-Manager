package ru.bcmanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import ru.bcmanager.domain.enums.Role;

/**
 * Пользователь системы (Plan.md, раздел 3.1).
 *
 * <p>Хранит учётные данные сотрудника управляющей компании. Пароль
 * сохраняется только в виде BCrypt-хэша.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "personal_workspace", nullable = false)
    private boolean personalWorkspace = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Требуется JPA. */
    protected User() {
    }

    /**
     * Создать пользователя.
     *
     * @param username     логин
     * @param passwordHash BCrypt-хэш пароля
     * @param fullName     ФИО сотрудника
     * @param role         роль доступа
     */
    public User(String username, String passwordHash, String fullName, Role role) {
        this.username = Objects.requireNonNull(username, "username");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.fullName = Objects.requireNonNull(fullName, "fullName");
        this.role = Objects.requireNonNull(role, "role");
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Признак полного доступа управляющего.
     *
     * @return {@code true} для роли {@code ADMIN}
     */
    public boolean isAdmin() {
        return role == Role.ADMIN;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isPersonalWorkspace() {
        return personalWorkspace;
    }

    public void setPersonalWorkspace(boolean personalWorkspace) {
        this.personalWorkspace = personalWorkspace;
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
        if (!(other instanceof User user)) {
            return false;
        }
        return id != null && Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "User{id=" + id + ", username='" + username + "', role=" + role + '}';
    }
}
