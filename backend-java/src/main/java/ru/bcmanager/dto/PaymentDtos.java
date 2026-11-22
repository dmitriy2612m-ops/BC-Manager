package ru.bcmanager.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO платежей и биллинга (Plan.md, раздел 5.5).
 */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * Запись графика платежей для выдачи.
     *
     * @param id            идентификатор
     * @param contractId    договор
     * @param contractNumber номер договора
     * @param periodNumber  номер периода
     * @param dueDate       крайняя дата оплаты
     * @param amountDue     сумма к оплате
     * @param paidAmount    оплачено
     * @param paidDate      дата оплаты
     * @param penaltyAmount пеня
     * @param status        статус
     */
    public record PaymentResponse(
            Long id,
            Long contractId,
            String contractNumber,
            Integer periodNumber,
            LocalDate dueDate,
            BigDecimal amountDue,
            BigDecimal paidAmount,
            LocalDate paidDate,
            BigDecimal penaltyAmount,
            String status) {
    }

    /**
     * Запрос регистрации оплаты.
     *
     * @param amount   сумма к зачислению
     * @param paidDate дата фактической оплаты
     */
    public record PayRequest(
            @NotNull(message = "Сумма оплаты обязательна")
            @Positive(message = "Сумма оплаты должна быть больше нуля")
            BigDecimal amount,

            @NotNull(message = "Дата оплаты обязательна")
            LocalDate paidDate) {
    }

    /**
     * Запрос пересчёта пени на контрольную дату.
     *
     * @param calculationDate контрольная дата
     */
    public record RecalculateRequest(
            @NotNull(message = "Контрольная дата обязательна")
            LocalDate calculationDate) {
    }

    /**
     * Детализация по просроченному платежу.
     *
     * @param paymentId    идентификатор платежа
     * @param contractId   договор
     * @param periodNumber номер периода
     * @param dueDate      крайняя дата оплаты
     * @param overdueDays  дней просрочки
     * @param debt         непогашенный остаток
     * @param penalty      начисленная пеня
     * @param rate         эффективная ставка, %
     * @param capped       сработал ли лимит 25%
     * @param status       итоговый статус
     */
    public record OverdueDetail(
            Long paymentId,
            Long contractId,
            Integer periodNumber,
            LocalDate dueDate,
            long overdueDays,
            BigDecimal debt,
            BigDecimal penalty,
            BigDecimal rate,
            boolean capped,
            String status) {
    }

    /**
     * Агрегат долга по договору.
     *
     * @param contractId      договор
     * @param debt            суммарный долг
     * @param penalty         суммарная пеня
     * @param overduePayments количество просроченных платежей
     */
    public record ContractDebtRow(
            Long contractId, BigDecimal debt, BigDecimal penalty, int overduePayments) {
    }

    /**
     * Ответ пересчёта пени.
     *
     * @param calculationDate контрольная дата
     * @param payments        детализация по платежам
     * @param contracts       агрегат по договорам
     * @param totalDebt       суммарный долг
     * @param totalPenalty    суммарная пеня
     */
    public record DebtReportResponse(
            LocalDate calculationDate,
            List<OverdueDetail> payments,
            List<ContractDebtRow> contracts,
            BigDecimal totalDebt,
            BigDecimal totalPenalty) {
    }
}
