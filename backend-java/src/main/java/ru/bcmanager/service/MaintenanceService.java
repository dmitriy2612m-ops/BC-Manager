package ru.bcmanager.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.dto.DtoMapper;
import ru.bcmanager.dto.MaintenanceDtos;
import ru.bcmanager.domain.enums.MaintenanceStatus;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.repository.MaintenanceRequestRepository;
import ru.bcmanager.repository.PremisesRepository;
import ru.bcmanager.repository.TenantRepository;

/**
 * Ведение заявок на техобслуживание (Plan.md, раздел 5.6).
 *
 * <p>CRUD-операции заявок и их статусов; приоритетная выдача выполняется
 * сервисом {@link MaintenanceQueueService} (Алгоритм 5).
 */
@Service
public class MaintenanceService {

    private final MaintenanceRequestRepository requestRepository;

    private final PremisesRepository premisesRepository;

    private final TenantRepository tenantRepository;

    private final MaintenanceQueueService queueService;

    private final DtoMapper mapper;

    private final WorkspaceContextService workspaceContext;

    /**
     * Создать сервис заявок.
     *
     * @param requestRepository  репозиторий заявок
     * @param premisesRepository репозиторий помещений
     * @param tenantRepository   репозиторий арендаторов
     * @param queueService       сервис приоритетной очереди
     * @param mapper             преобразователь в DTO
     * @param workspaceContext   контекст рабочего пространства
     */
    public MaintenanceService(
            MaintenanceRequestRepository requestRepository,
            PremisesRepository premisesRepository,
            TenantRepository tenantRepository,
            MaintenanceQueueService queueService,
            DtoMapper mapper,
            WorkspaceContextService workspaceContext) {
        this.requestRepository = requestRepository;
        this.premisesRepository = premisesRepository;
        this.tenantRepository = tenantRepository;
        this.queueService = queueService;
        this.mapper = mapper;
        this.workspaceContext = workspaceContext;
    }

    /**
     * Найти заявку по идентификатору.
     *
     * @param id идентификатор
     * @return заявка
     * @throws java.util.NoSuchElementException если заявка не найдена
     */
    @Transactional(readOnly = true)
    public MaintenanceRequest findById(Long id) {
        Long ownerId = workspaceContext.effectiveOwnerId();
        java.util.Optional<MaintenanceRequest> found = ownerId == null
                ? requestRepository.findByIdSharedWithDetails(id)
                : requestRepository.findByIdAndOwnerIdWithDetails(id, ownerId);
        return found.orElseThrow(() -> new java.util.NoSuchElementException(
                "Заявка с id=" + id + " не найдена"));
    }

    /**
     * Все заявки в порядке поступления.
     *
     * @return список заявок по возрастанию времени создания
     */
    @Transactional(readOnly = true)
    public List<MaintenanceRequest> findAll() {
        Long ownerId = workspaceContext.effectiveOwnerId();
        List<MaintenanceRequest> requests = new ArrayList<>(ownerId == null
                ? requestRepository.findAllSharedWithDetails()
                : requestRepository.findAllByOwnerIdWithDetails(ownerId));
        requests.sort((left, right) -> {
            int byCreated = left.getCreatedAt().compareTo(right.getCreatedAt());
            return byCreated != 0 ? byCreated : Long.compare(left.getId(), right.getId());
        });
        return requests;
    }

    /**
     * Приоритетная очередь активных заявок.
     *
     * @param now контрольный момент времени
     * @return записи очереди по Алгоритму 5
     */
    @Transactional(readOnly = true)
    public List<MaintenanceQueueService.QueueEntry> prioritized(LocalDateTime now) {
        Long ownerId = workspaceContext.effectiveOwnerId();
        List<MaintenanceRequest> active = ownerId == null
                ? requestRepository.findActiveSharedWithDetails()
                : requestRepository.findActiveByOwnerIdWithDetails(ownerId);
        return queueService.prioritize(active, now);
    }

    /**
     * Создать заявку.
     *
     * @param premisesId помещение
     * @param tenantId   инициатор (может быть {@code null})
     * @param title      тема
     * @param description описание
     * @param category   категория
     * @param priority   приоритет
     * @return сохранённая заявка
     */
    @Transactional
    public MaintenanceRequest create(
            Long premisesId,
            Long tenantId,
            String title,
            String description,
            ru.bcmanager.domain.enums.MaintenanceCategory category,
            ru.bcmanager.domain.enums.MaintenancePriority priority) {
        Long ownerId = workspaceContext.effectiveOwnerId();
        java.util.Optional<Premises> foundPremises = ownerId == null
                ? premisesRepository.findByIdAndOwnerIsNull(premisesId)
                : premisesRepository.findByIdAndOwnerId(premisesId, ownerId);
        Premises premises = foundPremises.orElseThrow(() ->
                new java.util.NoSuchElementException(
                        "Помещение с id=" + premisesId + " не найдено"));
        Tenant tenant = null;
        if (tenantId != null) {
            java.util.Optional<Tenant> foundTenant = ownerId == null
                    ? tenantRepository.findByIdAndOwnerIsNull(tenantId)
                    : tenantRepository.findByIdAndOwnerId(tenantId, ownerId);
            tenant = foundTenant.orElseThrow(() ->
                    new java.util.NoSuchElementException(
                            "Арендатор с id=" + tenantId + " не найден"));
        }
        MaintenanceRequest request = new MaintenanceRequest(
                premises, tenant, title, description, category, priority);
        request.setOwner(workspaceContext.effectiveOwner());
        return requestRepository.save(request);
    }

    /**
     * Создать заявку и вернуть DTO (отображение внутри транзакции).
     *
     * @param premisesId  помещение
     * @param tenantId    инициатор (может быть {@code null})
     * @param title       тема
     * @param description описание
     * @param category    категория
     * @param priority    приоритет
     * @return DTO сохранённой заявки
     */
    @Transactional
    public MaintenanceDtos.MaintenanceResponse createResponse(
            Long premisesId,
            Long tenantId,
            String title,
            String description,
            ru.bcmanager.domain.enums.MaintenanceCategory category,
            ru.bcmanager.domain.enums.MaintenancePriority priority) {
        MaintenanceRequest saved = create(
                premisesId, tenantId, title, description, category, priority);
        return mapper.toMaintenance(saved, null);
    }

    /**
     * Сменить статус заявки.
     *
     * <p>При переводе в {@code RESOLVED} фиксируется время закрытия; возврат
     * из закрытого состояния очищает его.
     *
     * @param id     идентификатор
     * @param status новый статус
     * @return сохранённая заявка
     */
    @Transactional
    public MaintenanceRequest changeStatus(Long id, MaintenanceStatus status) {
        MaintenanceRequest request = findById(id);
        if (status == MaintenanceStatus.RESOLVED) {
            request.resolve(LocalDateTime.now());
        } else {
            request.setStatus(status);
            request.setResolvedAt(null);
        }
        return requestRepository.save(request);
    }

    /**
     * Сменить статус заявки и вернуть DTO (отображение внутри транзакции).
     *
     * @param id     идентификатор
     * @param status новый статус
     * @return DTO обновлённой заявки
     */
    @Transactional
    public MaintenanceDtos.MaintenanceResponse changeStatusResponse(
            Long id, MaintenanceStatus status) {
        return mapper.toMaintenance(changeStatus(id, status), null);
    }
}
