package ru.bcmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.enums.PaymentStatus;
import ru.bcmanager.dto.DtoMapper;
import ru.bcmanager.dto.PaymentDtos;
import ru.bcmanager.service.BillingService;
import ru.bcmanager.service.PaymentService;
import ru.bcmanager.service.RegistryReadService;

/**
 * Платежи и биллинг (Plan.md, раздел 5.5).
 *
 * <p>Реестр начислений с фильтрами, регистрация оплаты и пересчёт пени на
 * дату по Алгоритму 4 через {@link BillingService}.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Платежи", description = "График начислений, оплата и пересчёт пени")
public class PaymentController {

    private final PaymentService paymentService;

    private final RegistryReadService registryReadService;

    private final DtoMapper mapper;

    /**
     * Создать контроллер платежей.
     *
     * @param paymentService     сервис платежей
     * @param registryReadService чтение реестров внутри транзакции
     * @param mapper             преобразователь в DTO
     */
    public PaymentController(
            PaymentService paymentService,
            RegistryReadService registryReadService,
            DtoMapper mapper) {
        this.paymentService = paymentService;
        this.registryReadService = registryReadService;
        this.mapper = mapper;
    }

    /**
     * Список платежей с фильтрами.
     *
     * @param contractId  договор
     * @param status      статус
     * @param overdueOnly только просроченные
     * @param asOf        контрольная дата расчёта просрочки
     * @return реестр платежей
     */
    @GetMapping
    @Operation(summary = "Список платежей (фильтры: contractId, status, overdueOnly)")
    public List<PaymentDtos.PaymentResponse> findAll(
            @RequestParam(required = false) Long contractId,
            @RequestParam(required = false) PaymentStatus status,
            @RequestParam(defaultValue = "false") boolean overdueOnly,
            @RequestParam(required = false) java.time.LocalDate asOf) {
        return registryReadService.findPayments(contractId, status, overdueOnly, asOf);
    }

    /**
     * Регистрация оплаты по платежу.
     *
     * @param id      идентификатор платежа
     * @param request сумма и дата оплаты
     * @return обновлённый платёж
     */
    @PostMapping("/{id}/pay")
    @Operation(summary = "Внести оплату (полную или частичную)")
    public PaymentDtos.PaymentResponse pay(
            @PathVariable Long id, @Valid @RequestBody PaymentDtos.PayRequest request) {
        Payment payment = paymentService.pay(id, request.amount(), request.paidDate());
        return mapper.toPayment(payment);
    }

    /**
     * Пересчёт просрочек и пени на дату (Алгоритм 4).
     *
     * @param request контрольная дата
     * @return отчёт по задолженности
     */
    @PostMapping("/recalculate-penalties")
    @Operation(summary = "Пересчитать пени на указанную дату")
    public PaymentDtos.DebtReportResponse recalculatePenalties(
            @Valid @RequestBody PaymentDtos.RecalculateRequest request) {
        BillingService.DebtReport report =
                paymentService.recalculatePenalties(request.calculationDate());

        List<PaymentDtos.OverdueDetail> payments = new ArrayList<>();
        for (BillingService.PaymentDetail detail : report.payments()) {
            payments.add(new PaymentDtos.OverdueDetail(
                    detail.paymentId(),
                    detail.contractId(),
                    detail.periodNumber(),
                    detail.dueDate(),
                    detail.overdueDays(),
                    detail.debt(),
                    detail.penalty(),
                    detail.rate(),
                    detail.capped(),
                    detail.status().name()));
        }

        List<PaymentDtos.ContractDebtRow> contracts = new ArrayList<>();
        for (BillingService.ContractDebt aggregate : report.contracts()) {
            contracts.add(new PaymentDtos.ContractDebtRow(
                    aggregate.contractId(),
                    aggregate.debt(),
                    aggregate.penalty(),
                    aggregate.overduePayments()));
        }

        return new PaymentDtos.DebtReportResponse(
                report.calculationDate(),
                payments,
                contracts,
                report.totalDebt(),
                report.totalPenalty());
    }
}
