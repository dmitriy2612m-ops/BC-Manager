package ru.bcmanager.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.bcmanager.domain.enums.ContractStatus;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.repository.PremisesRepository;

/**
 * CRUD-сервис реестра помещений (Plan.md, раздел 5.2).
 *
 * <p>Удаление разрешено только при отсутствии активных договоров: проверка
 * выполняется по репозиторию до вызова {@code delete}. Фильтрация выдачи
 * выполняется в памяти, потому что набор помещений бизнес-центра невелик.
 */
@Service
public class PremisesService {

    private final PremisesRepository premisesRepository;

    private final LeaseContractRepository contractRepository;

    private final WorkspaceContextService workspaceContext;

    /**
     * Создать сервис помещений.
     *
     * @param premisesRepository репозиторий помещений
     * @param contractRepository репозиторий договоров
     * @param workspaceContext   контекст рабочего пространства
     */
    public PremisesService(
            PremisesRepository premisesRepository,
            LeaseContractRepository contractRepository,
            WorkspaceContextService workspaceContext) {
        this.premisesRepository = premisesRepository;
        this.contractRepository = contractRepository;
        this.workspaceContext = workspaceContext;
    }

    /**
     * Все помещения текущего рабочего пространства.
     *
     * @return список помещений
     */
    @Transactional(readOnly = true)
    public List<Premises> findAllInWorkspace() {
        Long ownerId = workspaceContext.effectiveOwnerId();
        return ownerId == null
                ? premisesRepository.findByOwnerIsNull()
                : premisesRepository.findByOwnerId(ownerId);
    }

    /**
     * Список помещений с фильтрами.
     *
     * @param type     тип помещения (может быть {@code null})
     * @param status   статус (может быть {@code null})
     * @param floor    этаж (может быть {@code null})
     * @param building корпус (может быть {@code null})
     * @param minArea  минимальная площадь (может быть {@code null})
     * @param maxArea  максимальная площадь (может быть {@code null})
     * @return отфильтрованный список по возрастанию кода
     */
    @Transactional(readOnly = true)
    public List<Premises> findAll(
            PremisesType type,
            PremisesStatus status,
            Integer floor,
            String building,
            java.math.BigDecimal minArea,
            java.math.BigDecimal maxArea) {
        List<Premises> result = new ArrayList<>();
        for (Premises premises : findAllInWorkspace()) {
            if (type != null && premises.getPremisesType() != type) {
                continue;
            }
            if (status != null && premises.getStatus() != status) {
                continue;
            }
            if (floor != null && !floor.equals(premises.getFloor())) {
                continue;
            }
            if (building != null
                    && !building.isBlank()
                    && !building.equalsIgnoreCase(premises.getBuilding())) {
                continue;
            }
            if (minArea != null && premises.getAreaSqm().compareTo(minArea) < 0) {
                continue;
            }
            if (maxArea != null && premises.getAreaSqm().compareTo(maxArea) > 0) {
                continue;
            }
            result.add(premises);
        }
        result.sort((left, right) -> left.getCode().compareTo(right.getCode()));
        return result;
    }

    /**
     * Найти помещение по идентификатору.
     *
     * @param id идентификатор
     * @return помещение
     * @throws java.util.NoSuchElementException если помещение не найдено
     */
    @Transactional(readOnly = true)
    public Premises findById(Long id) {
        Long ownerId = workspaceContext.effectiveOwnerId();
        java.util.Optional<Premises> found = ownerId == null
                ? premisesRepository.findByIdAndOwnerIsNull(id)
                : premisesRepository.findByIdAndOwnerId(id, ownerId);
        return found.orElseThrow(() -> new java.util.NoSuchElementException(
                "Помещение с id=" + id + " не найдено"));
    }

    /**
     * Создать помещение.
     *
     * @param premises помещение (наследник {@code Premises})
     * @return сохранённая сущность с присвоенным идентификатором
     */
    @Transactional
    public Premises create(Premises premises) {
        Long ownerId = workspaceContext.effectiveOwnerId();
        boolean exists = ownerId == null
                ? premisesRepository.existsByCodeAndOwnerIsNull(premises.getCode())
                : premisesRepository.existsByCodeAndOwnerId(premises.getCode(), ownerId);
        if (exists) {
            throw new IllegalArgumentException(
                    "Помещение с кодом " + premises.getCode() + " уже существует");
        }
        premises.setOwner(workspaceContext.effectiveOwner());
        return premisesRepository.save(premises);
    }

    /**
     * Обновить помещение.
     *
     * @param id       идентификатор
     * @param premises новые значения
     * @return сохранённая сущность
     */
    @Transactional
    public Premises update(Long id, Premises premises) {
        Premises existing = findById(id);
        Long ownerId = workspaceContext.effectiveOwnerId();
        boolean duplicateCode = ownerId == null
                ? premisesRepository.existsByCodeAndOwnerIsNullAndIdNot(premises.getCode(), id)
                : premisesRepository.existsByCodeAndOwnerIdAndIdNot(premises.getCode(), ownerId, id);
        if (duplicateCode) {
            throw new IllegalArgumentException(
                    "Помещение с кодом " + premises.getCode() + " уже существует");
        }
        existing.setCode(premises.getCode());
        existing.setBuilding(premises.getBuilding());
        existing.setFloor(premises.getFloor());
        existing.setAreaSqm(premises.getAreaSqm());
        existing.setBaseRatePerSqm(premises.getBaseRatePerSqm());
        existing.setStatus(premises.getStatus());
        existing.setDescription(premises.getDescription());
        existing.setHasFurniture(premises.isHasFurniture());
        existing.setHasShowcase(premises.isHasShowcase());
        existing.setCapacityPeople(premises.getCapacityPeople());
        existing.setCovered(premises.isCovered());
        return premisesRepository.save(existing);
    }

    /**
     * Удалить помещение, если нет активных договоров.
     *
     * @param id идентификатор
     * @throws IllegalStateException если у помещения есть действующие договоры
     */
    @Transactional
    public void delete(Long id) {
        Premises premises = findById(id);
        boolean hasActiveContracts = contractRepository.existsByPremisesIdAndStatusIn(
                id, List.of(ContractStatus.ACTIVE, ContractStatus.DRAFT));
        if (hasActiveContracts) {
            throw new IllegalStateException(
                    "Нельзя удалить помещение " + premises.getCode() + ": есть действующие договоры");
        }
        premisesRepository.delete(premises);
    }
}
