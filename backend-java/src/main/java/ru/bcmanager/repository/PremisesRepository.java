package ru.bcmanager.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;
import ru.bcmanager.domain.premises.Premises;

/**
 * Spring Data JPA репозиторий помещений.
 *
 * <p>Полиморфные выборки по дискриминатору {@code premises_type} выполняются
 * через {@link #findByPremisesType(PremisesType)}: Hibernate сам возвращает
 * экземпляры нужного наследника ({@code OfficeSpace}, {@code RetailSpace},
 * {@code ConferenceRoom} или {@code ParkingSpot}).
 */
@Repository
public interface PremisesRepository extends JpaRepository<Premises, Long> {

    /**
     * Найти помещение по коду.
     *
     * @param code код помещения
     * @return помещение, если найдено
     */
    Optional<Premises> findByCode(String code);

    /**
     * Проверить существование кода помещения.
     *
     * @param code код помещения
     * @return {@code true}, если код уже используется
     */
    boolean existsByCode(String code);

    /**
     * Найти помещения по типу.
     *
     * @param premisesType тип помещения
     * @return полиморфный список наследников
     */
    List<Premises> findByPremisesType(PremisesType premisesType);

    /**
     * Найти помещения по статусу.
     *
     * @param status статус доступности
     * @return список помещений
     */
    List<Premises> findByStatus(PremisesStatus status);

    /**
     * Найти помещения по типу и статусу (использует индекс
     * {@code idx_premises_type_status}).
     *
     * @param premisesType тип помещения
     * @param status       статус доступности
     * @return список помещений
     */
    List<Premises> findByPremisesTypeAndStatus(PremisesType premisesType, PremisesStatus status);

    /**
     * Найти помещения по корпусу.
     *
     * @param building название корпуса
     * @return список помещений
     */
    List<Premises> findByBuilding(String building);

    /**
     * Найти помещения по этажу.
     *
     * @param floor номер этажа
     * @return список помещений
     */
    List<Premises> findByFloor(Integer floor);

    List<Premises> findByOwnerIsNull();

    List<Premises> findByOwnerId(Long ownerId);

    Optional<Premises> findByIdAndOwnerIsNull(Long id);

    Optional<Premises> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByCodeAndOwnerIsNull(String code);

    boolean existsByCodeAndOwnerId(String code, Long ownerId);

    boolean existsByCodeAndOwnerIsNullAndIdNot(String code, Long id);

    boolean existsByCodeAndOwnerIdAndIdNot(String code, Long ownerId, Long id);
}
