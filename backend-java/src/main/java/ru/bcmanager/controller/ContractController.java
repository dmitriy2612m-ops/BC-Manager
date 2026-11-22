package ru.bcmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.enums.ContractStatus;
import ru.bcmanager.dto.ContractDtos;
import ru.bcmanager.dto.DtoMapper;
import ru.bcmanager.service.ContractService;
import ru.bcmanager.service.LeasePricingService;
import ru.bcmanager.service.TenantService;

/**
 * Договоры аренды (Plan.md, раздел 5.4).
 *
 * <p>Предпросмотр договора прогоняет Алгоритмы 1 и 3 без записи в БД и
 * возвращает детализацию расчёта вместе с графиком платежей.
 */
@RestController
@RequestMapping("/api/v1/contracts")
@Tag(name = "Договоры аренды", description = "Реестр договоров, предпросмотр и смена статусов")
public class ContractController {

    private final ContractService contractService;

    private final TenantService tenantService;

    private final DtoMapper mapper;

    /**
     * Создать контроллер договоров.
     *
     * @param contractService сервис договоров
     * @param tenantService   сервис арендаторов
     * @param mapper          преобразователь в DTO
     * @param userRepository  репозиторий пользователей (автор договора)
     */
    public ContractController(
            ContractService contractService,
            TenantService tenantService,
            DtoMapper mapper,
            ru.bcmanager.repository.UserRepository userRepository) {
        this.contractService = contractService;
        this.tenantService = tenantService;
        this.mapper = mapper;
        this.userRepository = userRepository;
    }

    private final ru.bcmanager.repository.UserRepository userRepository;

    /**
     * Список договоров с фильтрами.
     *
     * @param status     статус
     * @param tenantId   арендатор
     * @param premisesId помещение
     * @return список кратких карточек
     */
    @GetMapping
    @Operation(summary = "Список договоров с фильтрами")
    public List<ContractDtos.ContractSummary> findAll(
            @RequestParam(required = false) ContractStatus status,
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long premisesId) {
        return mapper.toContractSummaries(
                contractService.findAll(status, tenantId, premisesId));
    }

    /**
     * Детали договора вместе с графиком платежей.
     *
     * @param id идентификатор
     * @return полная карточка договора
     */
    @GetMapping("/{id}")
    @Operation(summary = "Договор с графиком платежей")
    public ContractDtos.ContractDetails findById(@PathVariable Long id) {
        LeaseContract contract = contractService.findById(id);
        List<Payment> payments = contractService.schedule(id);

        List<ContractDtos.PaymentRow> rows = new ArrayList<>(payments.size());
        for (Payment payment : payments) {
            rows.add(mapper.toPaymentRow(payment));
        }
        return new ContractDtos.ContractDetails(
                mapper.toContractSummary(contract),
                contract.getDiscountPercent(),
                contract.getDepositAmount(),
                contract.getCreatedBy() == null ? null : contract.getCreatedBy().getFullName(),
                contract.getCreatedAt(),
                rows);
    }

    /**
     * Предпросмотр договора (Алгоритмы 1 и 3) без сохранения.
     *
     * @param request параметры договора
     * @return расчёт стоимости и предпросмотр графика
     */
    @PostMapping("/preview")
    @Operation(summary = "Предпросмотр расчёта договора")
    public ContractDtos.PreviewResponse preview(
            @Valid @RequestBody ContractDtos.PreviewRequest request) {
        ContractService.PreviewResult result = contractService.preview(
                request.premisesId(),
                request.startDate(),
                request.endDate(),
                request.excludeContractId());
        LeasePricingService.RentCalculation calculation = result.calculation();

        List<ContractDtos.ScheduleRow> schedule = new ArrayList<>();
        for (LeasePricingService.ScheduleEntry entry : calculation.schedule()) {
            schedule.add(mapper.toScheduleRow(entry));
        }

        return new ContractDtos.PreviewResponse(
                request.premisesId(),
                result.premisesCode(),
                result.premisesType(),
                result.areaSqm(),
                request.startDate(),
                request.endDate(),
                calculation.months(),
                calculation.typedMonthly(),
                calculation.opexMonthly(),
                calculation.discountPercent(),
                calculation.netMonthly(),
                calculation.vatAmount(),
                calculation.totalMonthly(),
                schedule);
    }

    /**
     * Оформить договор.
     *
     * @param request параметры договора
     * @return созданный договор
     */
    @PostMapping
    @Operation(summary = "Оформить договор аренды")
    public ResponseEntity<ContractDtos.ContractSummary> create(
            @Valid @RequestBody ContractDtos.CreateContractRequest request,
            java.security.Principal principal) {
        Tenant tenant = tenantService.findById(request.tenantId());
        ru.bcmanager.domain.User author = principal == null
                ? null
                : userRepository.findByUsername(principal.getName()).orElse(null);
        LeaseContract saved = contractService.create(
                request.contractNumber(),
                tenant,
                request.premisesId(),
                request.startDate(),
                request.endDate(),
                request.activate(),
                author);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(mapper.toContractSummary(saved));
    }

    /**
     * Сменить статус договора.
     *
     * @param id      идентификатор
     * @param request новый статус
     * @return обновлённая карточка
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "Сменить статус договора")
    public ContractDtos.ContractSummary changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody ContractDtos.ChangeStatusRequest request) {
        return mapper.toContractSummary(
                contractService.changeStatus(id, request.status()));
    }
}
