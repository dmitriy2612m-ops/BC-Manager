package ru.bcmanager.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.enums.MaintenanceStatus;

/**
 * Spring Data JPA репозиторий заявок на техническое обслуживание.
 */
@Repository
public interface MaintenanceRequestRepository extends JpaRepository<MaintenanceRequest, Long> {

    /**
     * Найти заявки по статусу.
     *
     * @param status статус заявки
     * @return список заявок
     */
    List<MaintenanceRequest> findByStatus(MaintenanceStatus status);

    /**
     * Найти заявки в заданных статусах.
     *
     * @param statuses интересующие статусы
     * @return список заявок
     */
    List<MaintenanceRequest> findByStatusIn(Collection<MaintenanceStatus> statuses);

    /**
     * Найти активные заявки ({@code OPEN} и {@code IN_PROGRESS}).
     *
     * @return список активных заявок
     */
    @Query(
            """
            SELECT m FROM MaintenanceRequest m
            WHERE m.status IN (ru.bcmanager.domain.enums.MaintenanceStatus.OPEN,
                               ru.bcmanager.domain.enums.MaintenanceStatus.IN_PROGRESS)
            """)
    List<MaintenanceRequest> findActive();

    /**
     * Найти открытые заявки помещения.
     *
     * @param premisesId идентификатор помещения
     * @return список заявок
     */
    List<MaintenanceRequest> findByPremisesId(Long premisesId);
}
