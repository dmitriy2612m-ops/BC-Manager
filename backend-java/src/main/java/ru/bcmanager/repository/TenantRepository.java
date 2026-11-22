package ru.bcmanager.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.bcmanager.domain.Tenant;

/**
 * Spring Data JPA репозиторий арендаторов.
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    /**
     * Найти арендатора по ИНН.
     *
     * @param inn ИНН
     * @return арендатор, если найден
     */
    Optional<Tenant> findByInn(String inn);

    /**
     * Проверить существование ИНН.
     *
     * @param inn ИНН
     * @return {@code true}, если ИНН уже используется
     */
    boolean existsByInn(String inn);

    /**
     * Поиск по названию компании или ИНН без учёта регистра.
     *
     * @param query подстрока для поиска
     * @return список найденных арендаторов
     */
    @Query(
            """
            SELECT t FROM Tenant t
            WHERE LOWER(t.companyName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR t.inn LIKE CONCAT('%', :query, '%')
            """)
    List<Tenant> search(@Param("query") String query);

    /**
     * Найти активных арендаторов.
     *
     * @return список активных контрагентов
     */
    List<Tenant> findByActiveTrue();
}
