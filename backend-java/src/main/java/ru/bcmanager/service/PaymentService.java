package ru.bcmanager.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.enums.PaymentStatus;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.repository.PaymentRepository;

/**
 * Реестр платежей, фиксация оплат и пересчёт пени (Plan.md, раздел 5.5).
 *
 * <p>Сервис — тонкая обёртка над {@link BillingService}: выбирает платежи по
 * фильтрам, применяет оплату и сохраняет пересчитанные статусы и пеню.
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    private final LeaseContractRepository contractRepository;

    private final BillingService billingService;

    /**
     * Создать сервис платежей.
     *
     * @param paymentRepository  репозиторий платежей
     * @param contractRepository репозиторий договоров
     * @param billingService     сервис биллинга
     */
    public PaymentService(
            PaymentRepository paymentRepository,
            LeaseContractRepository contractRepository,
            BillingService billingService) {
        this.paymentRepository = paymentRepository;
        this.contractRepository = contractRepository;
        this.billingService = billingService;
    }

    /**
     * Список платежей с фильтрами.
     *
     * @param contractId договор (может быть {@code null})
     * @param status     статус (может быть {@code null})
     * @param overdueOnly только просроченные (текущий статус {@code OVERDUE})
     * @param asOf       контрольная дата для расчёта просрочки (может быть {@code null})
     * @return платежи по возрастанию договора и периода
     */
    @Transactional(readOnly = true)
    public List<Payment> findAll(
            Long contractId, PaymentStatus status, boolean overdueOnly, LocalDate asOf) {
        List<Payment> source;
        if (contractId != null) {
            source = paymentRepository.findByContractIdOrderByPeriodNumberAsc(contractId);
        } else {
            source = paymentRepository.findAll();
        }

        List<Payment> result = new ArrayList<>();
        for (Payment payment : source) {
            if (status != null && payment.getStatus() != status) {
                continue;
            }
            if (overdueOnly && !isOverdue(payment, asOf)) {
                continue;
            }
            result.add(payment);
        }
        result.sort((left, right) -> {
            int byContract = Long.compare(
                    left.getContract().getId(), right.getContract().getId());
            return byContract != 0
                    ? byContract
                    : Integer.compare(left.getPeriodNumber(), right.getPeriodNumber());
        });
        return result;
    }

    /**
     * Найти платёж по идентификатору.
     *
     * @param id идентификатор
     * @return платёж
     * @throws java.util.NoSuchElementException если платёж не найден
     */
    @Transactional(readOnly = true)
    public Payment findById(Long id) {
        return paymentRepository.findById(id).orElseThrow(() -> new java.util.NoSuchElementException(
                "Платёж с id=" + id + " не найден"));
    }

    /**
     * Зарегистрировать (возможно частичную) оплату.
     *
     * @param paymentId идентификатор платежа
     * @param amount    сумма к зачислению
     * @param paidDate  дата фактической оплаты
     * @return обновлённый платёж
     */
    @Transactional
    public Payment pay(Long paymentId, BigDecimal amount, LocalDate paidDate) {
        Payment payment = findById(paymentId);
        billingService.applyPayment(payment, amount, paidDate);
        return paymentRepository.save(payment);
    }

    /**
     * Пересчитать просрочки и пеню на контрольную дату.
     *
     * <p>Метод не только возвращает отчёт, но и фиксирует в платежах статус
     * {@code OVERDUE} и начисленную пеню — так данные сохраняются между
     * запросами. Агрегат включает все договоры, даже без долга, ради
     * детерминированности.
     *
     * @param calculationDate контрольная дата
     * @return отчёт по задолженности
     */
    @Transactional
    public BillingService.DebtReport recalculatePenalties(LocalDate calculationDate) {
        List<Payment> payments = paymentRepository.findAll();
        List<ru.bcmanager.domain.LeaseContract> contracts =
                contractRepository.findAllWithDetails();

        BillingService.DebtReport report =
                billingService.calculatePenaltiesAndDebt(payments, calculationDate, contracts);

        java.util.Map<Long, BillingService.PaymentDetail> detailsById = new java.util.HashMap<>();
        for (BillingService.PaymentDetail detail : report.payments()) {
            detailsById.put(detail.paymentId(), detail);
        }

        for (Payment payment : payments) {
            BillingService.PaymentDetail detail = detailsById.get(payment.getId());
            if (detail == null) {
                continue;
            }
            payment.setPenaltyAmount(detail.penalty());
            payment.setStatus(detail.status());
        }
        paymentRepository.saveAll(payments);

        return report;
    }

    private static boolean isOverdue(Payment payment, LocalDate asOf) {
        if (payment.getStatus() == PaymentStatus.PAID) {
            return false;
        }
        if (payment.getStatus() == PaymentStatus.OVERDUE) {
            return true;
        }
        return asOf != null && payment.getDueDate().isBefore(asOf);
    }
}
