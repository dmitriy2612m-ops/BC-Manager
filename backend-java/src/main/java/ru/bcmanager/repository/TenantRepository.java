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

    List<Tenant> findByOwnerIsNull();

    List<Tenant> findByOwnerId(Long ownerId);

    Optional<Tenant> findByIdAndOwnerIsNull(Long id);

    Optional<Tenant> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByInnAndOwnerIsNull(String inn);

    boolean existsByInnAndOwnerId(String inn, Long ownerId);

    boolean existsByInnAndOwnerIsNullAndIdNot(String inn, Long id);

    boolean existsByInnAndOwnerIdAndIdNot(String inn, Long ownerId, Long id);

    @Query(
            """
            SELECT t FROM Tenant t
            WHERE t.owner IS NULL
              AND (LOWER(t.companyName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR t.inn LIKE CONCAT('%', :query, '%'))
            """)
    List<Tenant> searchShared(@Param("query") String query);

    @Query(
            """
            SELECT t FROM Tenant t
            WHERE t.owner.id = :ownerId
              AND (LOWER(t.companyName) LIKE LOWER(CONCAT('%', :query, '%'))
                   OR t.inn LIKE CONCAT('%', :query, '%'))
            """)
    List<Tenant> searchByOwnerId(@Param("ownerId") Long ownerId, @Param("query") String query);
}
