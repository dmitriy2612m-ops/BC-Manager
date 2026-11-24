package ru.bcmanager.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.enums.PaymentStatus;

/**
 * Spring Data JPA репозиторий платежей.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /**
     * Найти платежи договора в порядке номеров периодов.
     *
     * @param contractId идентификатор договора
     * @return график платежей
     */
    List<Payment> findByContractIdOrderByPeriodNumberAsc(Long contractId);

    /**
     * Найти платежи по статусу.
     *
     * @param status статус платежа
     * @return список платежей
     */
    List<Payment> findByStatus(PaymentStatus status);

    /**
     * Найти платежи договора по статусу (использует индекс
     * {@code idx_payments_contract_status}).
     *
     * @param contractId идентификатор договора
     * @param status     статус платежа
     * @return список платежей
     */
    List<Payment> findByContractIdAndStatus(Long contractId, PaymentStatus status);

    /**
     * Найти все неоплаченные платежи.
     *
     * @return список неоплаченных платежей
     */
    @Query("SELECT p FROM Payment p WHERE p.status <> ru.bcmanager.domain.enums.PaymentStatus.PAID")
    List<Payment> findUnpaid();

    /**
     * Найти просроченные платежи на указанную дату (не оплачены и срок прошёл).
     *
     * @param calculationDate контрольная дата
     * @return список просроченных платежей
     */
    @Query(
            """
            SELECT p FROM Payment p
            WHERE p.status <> ru.bcmanager.domain.enums.PaymentStatus.PAID
              AND p.dueDate < :calculationDate
            ORDER BY p.contract.id ASC, p.periodNumber ASC
            """)
    List<Payment> findOverdueOn(@Param("calculationDate") java.time.LocalDate calculationDate);

    /**
     * Сумма начислений договора (для аналитики).
     *
     * @param contractId идентификатор договора
     * @return сумма {@code amount_due} по всем периодам
     */
    @Query("SELECT COALESCE(SUM(p.amountDue), 0) FROM Payment p WHERE p.contract.id = :contractId")
    java.math.BigDecimal sumAmountDueByContract(@Param("contractId") Long contractId);

    @Query("SELECT p FROM Payment p JOIN FETCH p.contract WHERE p.contract.owner IS NULL")
    List<Payment> findAllSharedWithContract();

    @Query("SELECT p FROM Payment p JOIN FETCH p.contract WHERE p.contract.owner.id = :ownerId")
    List<Payment> findAllByOwnerIdWithContract(@Param("ownerId") Long ownerId);

    @Query("SELECT p FROM Payment p JOIN FETCH p.contract"
            + " WHERE p.contract.id = :contractId AND p.contract.owner IS NULL"
            + " ORDER BY p.periodNumber ASC")
    List<Payment> findByContractIdSharedOrderByPeriodNumberAsc(@Param("contractId") Long contractId);

    @Query("SELECT p FROM Payment p JOIN FETCH p.contract"
            + " WHERE p.contract.id = :contractId AND p.contract.owner.id = :ownerId"
            + " ORDER BY p.periodNumber ASC")
    List<Payment> findByContractIdAndOwnerIdOrderByPeriodNumberAsc(
            @Param("contractId") Long contractId, @Param("ownerId") Long ownerId);

    @Query("SELECT p FROM Payment p JOIN FETCH p.contract WHERE p.id = :id AND p.contract.owner IS NULL")
    Optional<Payment> findByIdSharedWithContract(@Param("id") Long id);

    @Query("SELECT p FROM Payment p JOIN FETCH p.contract WHERE p.id = :id AND p.contract.owner.id = :ownerId")
    Optional<Payment> findByIdAndOwnerIdWithContract(@Param("id") Long id, @Param("ownerId") Long ownerId);
}
