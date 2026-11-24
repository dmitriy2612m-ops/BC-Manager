package ru.bcmanager.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.User;
import ru.bcmanager.domain.enums.ContractStatus;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.repository.PaymentRepository;
import ru.bcmanager.repository.PremisesRepository;

/**
 * Оформление и ведение договоров аренды (Plan.md, разделы 4.1 и 5.4).
 *
 * <p>Сервис связывает алгоритмы проверки конфликтов (1), расчёта стоимости
 * (3) и генерацию графика платежей: предпросмотр не пишет в БД, оформление
 * атомарно сохраняет договор, график платежей и статус помещения.
 *
 * <p>Статус помещения синхронизируется с договором: при активации помещение
 * переходит в {@code RENTED}, при завершении или расторжении — обратно в
 * {@code AVAILABLE}, если у него нет других действующих договоров.
 */
@Service
public class ContractService {

    private final LeaseContractRepository contractRepository;

    private final PremisesRepository premisesRepository;

    private final PaymentRepository paymentRepository;

    private final LeasePricingService pricingService;

    private final SpaceMatchingService matchingService;

    private final WorkspaceContextService workspaceContext;

    /**
     * Создать сервис договоров.
     *
     * @param contractRepository репозиторий договоров
     * @param premisesRepository репозиторий помещений
     * @param paymentRepository  репозиторий платежей
     * @param pricingService     сервис расчёта аренды
     * @param matchingService    сервис проверки конфликтов
     * @param workspaceContext   контекст рабочего пространства
     */
    public ContractService(
            LeaseContractRepository contractRepository,
            PremisesRepository premisesRepository,
            PaymentRepository paymentRepository,
            LeasePricingService pricingService,
            SpaceMatchingService matchingService,
            WorkspaceContextService workspaceContext) {
        this.contractRepository = contractRepository;
        this.premisesRepository = premisesRepository;
        this.paymentRepository = paymentRepository;
        this.pricingService = pricingService;
        this.matchingService = matchingService;
        this.workspaceContext = workspaceContext;
    }

    /**
     * Все договоры текущего рабочего пространства с подгруженными связями.
     *
     * @return список договоров
     */
    @Transactional(readOnly = true)
    public List<LeaseContract> findAllInWorkspaceWithDetails() {
        Long ownerId = workspaceContext.effectiveOwnerId();
        return ownerId == null
                ? contractRepository.findAllSharedWithDetails()
                : contractRepository.findAllByOwnerIdWithDetails(ownerId);
    }

    /**
     * Список договоров с фильтрами.
     *
     * @param status     статус (может быть {@code null})
     * @param tenantId   арендатор (может быть {@code null})
     * @param premisesId помещение (может быть {@code null})
     * @return отфильтрованный список по возрастанию номера
     */
    @Transactional(readOnly = true)
    public List<LeaseContract> findAll(
            ContractStatus status, Long tenantId, Long premisesId) {
        List<LeaseContract> result = new ArrayList<>();
        for (LeaseContract contract : findAllInWorkspaceWithDetails()) {
            if (status != null && contract.getStatus() != status) {
                continue;
            }
            if (tenantId != null
                    && !tenantId.equals(contract.getTenant().getId())) {
                continue;
            }
            if (premisesId != null
                    && !premisesId.equals(contract.getPremises().getId())) {
                continue;
            }
            result.add(contract);
        }
        result.sort((left, right) ->
                left.getContractNumber().compareTo(right.getContractNumber()));
        return result;
    }

    /**
     * Найти договор по идентификатору вместе с помещением и арендатором.
     *
     * @param id идентификатор
     * @return договор
     * @throws java.util.NoSuchElementException если договор не найден
     */
    @Transactional(readOnly = true)
    public LeaseContract findById(Long id) {
        Long ownerId = workspaceContext.effectiveOwnerId();
        java.util.Optional<LeaseContract> found = ownerId == null
                ? contractRepository.findByIdSharedWithDetails(id)
                : contractRepository.findByIdAndOwnerIdWithDetails(id, ownerId);
        return found.orElseThrow(() ->
                new java.util.NoSuchElementException("Договор с id=" + id + " не найден"));
    }

    /**
     * График платежей договора.
     *
     * @param contractId идентификатор договора
     * @return платежи по возрастанию периода
     */
    @Transactional(readOnly = true)
    public List<Payment> schedule(Long contractId) {
        findById(contractId);
        Long ownerId = workspaceContext.effectiveOwnerId();
        return ownerId == null
                ? paymentRepository.findByContractIdSharedOrderByPeriodNumberAsc(contractId)
                : paymentRepository.findByContractIdAndOwnerIdOrderByPeriodNumberAsc(contractId, ownerId);
    }

    /**
     * Предпросмотр договора без сохранения (Алгоритмы 1 и 3).
     *
     * @param premisesId       помещение
     * @param startDate        дата начала
     * @param endDate          дата окончания
     * @param excludeContractId договор, который правим (может быть {@code null})
     * @return результат проверки конфликта и расчёт с графиком
     * @throws IllegalStateException при пересечении дат
     */
    @Transactional(readOnly = true)
    public PreviewResult preview(
            Long premisesId,
            LocalDate startDate,
            LocalDate endDate,
            Long excludeContractId) {
        Premises premises = findPremises(premisesId);
        SpaceMatchingService.ConflictResult conflict = matchingService.checkLeaseConflict(
                findAllInWorkspaceWithDetails(),
                premisesId,
                startDate,
                endDate,
                excludeContractId);
        if (conflict.hasConflict()) {
            throw new IllegalStateException(missingConflictMessage(premises, conflict));
        }
        LeasePricingService.RentCalculation calculation = pricingService.calculateWithSchedule(
                premises, startDate, endDate, LeasePricingService.DEFAULT_VAT_RATE);
        return new PreviewResult(
                conflict,
                premises.getCode(),
                premises.getPremisesType().name(),
                premises.getAreaSqm(),
                calculation);
    }

    /**
     * Оформить договор: проверить конфликты, сохранить договор и график.
     *
     * @param contractNumber номер договора
     * @param tenant         арендатор
     * @param premisesId     помещение
     * @param startDate      дата начала
     * @param endDate        дата окончания
     * @param activate       активировать ли договор сразу
     * @return сохранённый договор
     */
    @Transactional
    public LeaseContract create(
            String contractNumber,
            Tenant tenant,
            Long premisesId,
            LocalDate startDate,
            LocalDate endDate,
            boolean activate) {
        return create(contractNumber, tenant, premisesId, startDate, endDate, activate, null);
    }

    /**
     * Оформить договор с указанием сотрудника, который его оформил.
     *
     * @param contractNumber номер договора
     * @param tenant         арендатор
     * @param premisesId     помещение
     * @param startDate      дата начала
     * @param endDate        дата окончания
     * @param activate       активировать ли договор сразу
     * @param createdBy      пользователь-оформитель (может быть {@code null})
     * @return сохранённый договор
     */
    @Transactional
    public LeaseContract create(
            String contractNumber,
            Tenant tenant,
            Long premisesId,
            LocalDate startDate,
            LocalDate endDate,
            boolean activate,
            User createdBy) {
        if (startDate == null || endDate == null || !endDate.isAfter(startDate)) {
            throw new IllegalArgumentException("Дата окончания должна быть позже даты начала");
        }
        if (tenant != null && !tenant.isActive()) {
            throw new IllegalArgumentException(
                    "Арендатор " + tenant.getCompanyName() + " неактивен");
        }
        Premises premises = findPremises(premisesId);
        SpaceMatchingService.ConflictResult conflict = matchingService.checkLeaseConflict(
                findAllInWorkspaceWithDetails(),
                premisesId,
                startDate,
                endDate,
                null);
        if (conflict.hasConflict()) {
            throw new IllegalStateException(missingConflictMessage(premises, conflict));
        }
        Long ownerId = workspaceContext.effectiveOwnerId();
        boolean numberExists = ownerId == null
                ? contractRepository.existsByContractNumberAndOwnerIsNull(contractNumber)
                : contractRepository.existsByContractNumberAndOwnerId(contractNumber, ownerId);
        if (numberExists) {
            throw new IllegalArgumentException(
                    "Договор с номером " + contractNumber + " уже существует");
        }

        LeasePricingService.RentCalculation calculation = pricingService.calculateWithSchedule(
                premises, startDate, endDate, LeasePricingService.DEFAULT_VAT_RATE);

        LeaseContract contract = new LeaseContract(
                contractNumber,
                premises,
                tenant,
                startDate,
                endDate,
                calculation.totalMonthly(),
                activate ? ContractStatus.ACTIVE : ContractStatus.DRAFT);
        contract.setDiscountPercent(calculation.discountPercent());
        contract.setDepositAmount(calculation.totalMonthly());
        contract.setCreatedBy(createdBy);
        contract.setOwner(workspaceContext.effectiveOwner());

        LeaseContract saved = contractRepository.save(contract);

        List<Payment> payments = new ArrayList<>();
        for (LeasePricingService.ScheduleEntry entry : calculation.schedule()) {
            payments.add(new Payment(
                    saved, entry.periodNumber(), entry.dueDate(), entry.amountDue()));
        }
        paymentRepository.saveAll(payments);

        if (activate) {
            syncPremisesStatus(premises, ContractStatus.ACTIVE);
        }
        return saved;
    }

    /**
     * Сменить статус договора и синхронизировать статус помещения.
     *
     * @param contractId идентификатор договора
     * @param newStatus  новый статус
     * @return сохранённый договор
     */
    @Transactional
    public LeaseContract changeStatus(Long contractId, ContractStatus newStatus) {
        LeaseContract contract = findById(contractId);
        contract.setStatus(newStatus);
        LeaseContract saved = contractRepository.save(contract);
        syncPremisesStatus(contract.getPremises(), newStatus);
        return saved;
    }

    private void syncPremisesStatus(Premises premises, ContractStatus contractStatus) {
        if (contractStatus == ContractStatus.ACTIVE) {
            premises.setStatus(PremisesStatus.RENTED);
            premisesRepository.save(premises);
            return;
        }
        if (contractStatus == ContractStatus.COMPLETED
                || contractStatus == ContractStatus.TERMINATED) {
            boolean hasOtherActive = contractRepository.existsByPremisesIdAndStatusIn(
                    premises.getId(), List.of(ContractStatus.ACTIVE));
            if (!hasOtherActive && premises.getStatus() == PremisesStatus.RENTED) {
                premises.setStatus(PremisesStatus.AVAILABLE);
                premisesRepository.save(premises);
            }
        }
    }

    private Premises findPremises(Long premisesId) {
        Long ownerId = workspaceContext.effectiveOwnerId();
        java.util.Optional<Premises> found = ownerId == null
                ? premisesRepository.findByIdAndOwnerIsNull(premisesId)
                : premisesRepository.findByIdAndOwnerId(premisesId, ownerId);
        return found.orElseThrow(() ->
                new java.util.NoSuchElementException(
                        "Помещение с id=" + premisesId + " не найдено"));
    }

    private static String missingConflictMessage(
            Premises premises, SpaceMatchingService.ConflictResult conflict) {
        return "Помещение " + premises.getCode() + " уже занято в указанный период (договор "
                + conflict.conflictingContractNumber() + ")";
    }

    /**
     * Результат предпросмотра договора.
     *
     * @param conflict     результат проверки конфликта (всегда без конфликта)
     * @param premisesCode код помещения
     * @param premisesType тип помещения
     * @param areaSqm      площадь помещения, м²
     * @param calculation  расчёт стоимости и график платежей
     */
    public record PreviewResult(
            SpaceMatchingService.ConflictResult conflict,
            String premisesCode,
            String premisesType,
            java.math.BigDecimal areaSqm,
            LeasePricingService.RentCalculation calculation) {
    }
}
