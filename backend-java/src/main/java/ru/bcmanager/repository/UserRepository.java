package ru.bcmanager.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.bcmanager.domain.User;

/**
 * Spring Data JPA репозиторий пользователей.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Найти пользователя по логину.
     *
     * @param username логин
     * @return пользователь, если найден
     */
    Optional<User> findByUsername(String username);

    /**
     * Проверить существование логина.
     *
     * @param username логин
     * @return {@code true}, если логин уже занят
     */
    boolean existsByUsername(String username);
}
