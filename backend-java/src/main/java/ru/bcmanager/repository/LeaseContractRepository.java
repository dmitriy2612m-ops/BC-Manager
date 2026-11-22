package ru.bcmanager.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.enums.ContractStatus;

/**
 * Spring Data JPA репозиторий договоров аренды.
 */
@Repository
public interface LeaseContractRepository extends JpaRepository<LeaseContract, Long> {

    /**
     * Найти договор по номеру.
     *
     * @param contractNumber номер договора
     * @return договор, если найден
     */
    Optional<LeaseContract> findByContractNumber(String contractNumber);

    /**
     * Проверить существование номера договора.
     *
     * @param contractNumber номер договора
     * @return {@code true}, если номер уже используется
     */
    boolean existsByContractNumber(String contractNumber);

    /**
     * Найти договоры по статусу.
     *
     * @param status статус договора
     * @return список договоров
     */
    List<LeaseContract> findByStatus(ContractStatus status);

    /**
     * Найти договоры по помещению.
     *
     * @param premisesId идентификатор помещения
     * @return список договоров
     */
    List<LeaseContract> findByPremisesId(Long premisesId);

    /**
     * Найти договоры по арендатору.
     *
     * @param tenantId идентификатор арендатора
     * @return список договоров
     */
    List<LeaseContract> findByTenantId(Long tenantId);

    /**
     * Найти договоры арендатора с подгруженными помещением и арендатором.
     *
     * @param tenantId идентификатор арендатора
     * @return список договоров с деталями
     */
    @Query("SELECT c FROM LeaseContract c JOIN FETCH c.premises JOIN FETCH c.tenant WHERE c.tenant.id = :tenantId")
    List<LeaseContract> findByTenantIdWithDetails(@Param("tenantId") Long tenantId);

    /**
     * Найти договоры помещения в заданных статусах (для проверки конфликтов
     * дат по индексу {@code idx_contracts_premises_dates}).
     *
     * @param premisesId идентификатор помещения
     * @param statuses   интересующие статусы
     * @return список договоров
     */
    List<LeaseContract> findByPremisesIdAndStatusIn(
            Long premisesId, Collection<ContractStatus> statuses);

    /**
     * Есть ли у помещения договоры в заданных статусах.
     *
     * @param premisesId идентификатор помещения
     * @param statuses   интересующие статусы
     * @return {@code true}, если такие договоры есть
     */
    boolean existsByPremisesIdAndStatusIn(Long premisesId, Collection<ContractStatus> statuses);

    /**
     * Найти активные договоры, истекающие в заданном окне дат.
     *
     * @param from нижняя граница интервала окончания (включительно)
     * @param to   верхняя граница интервала окончания (включительно)
     * @return список истекающих договоров
     */
    @Query(
            """
            SELECT c FROM LeaseContract c
            WHERE c.status = ru.bcmanager.domain.enums.ContractStatus.ACTIVE
              AND c.endDate >= :from
              AND c.endDate <= :to
            ORDER BY c.endDate ASC, c.id ASC
            """)
    List<LeaseContract> findExpiringBetween(
            @Param("from") java.time.LocalDate from, @Param("to") java.time.LocalDate to);

    /**
     * Найти все договоры с подгруженными помещением и арендатором.
     *
     * @return список договоров
     */
    @Query("SELECT c FROM LeaseContract c JOIN FETCH c.premises JOIN FETCH c.tenant")
    List<LeaseContract> findAllWithDetails();

    /**
     * Найти договор с подгруженными графиком платежей.
     *
     * <p>Загрузка деталей выполняется отдельным запросом платежей, чтобы не
     * смешивать {@code JOIN FETCH} коллекций с пагинацией.
     *
     * @param id идентификатор договора
     * @return договор, если найден
     */
    @Query("SELECT c FROM LeaseContract c JOIN FETCH c.premises JOIN FETCH c.tenant WHERE c.id = :id")
    Optional<LeaseContract> findByIdWithDetails(@Param("id") Long id);
}
