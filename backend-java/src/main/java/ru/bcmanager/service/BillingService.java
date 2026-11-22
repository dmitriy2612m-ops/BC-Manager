package ru.bcmanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.enums.PaymentStatus;

/**
 * Фиксация оплат, пересчёт просрочек и пени (Plan.md, раздел 4.1, Алгоритм 4).
 *
 * <p>Правила пени: 0.1% в день за первые 30 дней просрочки и 0.2% за каждый
 * следующий день, но не более 25% от непогашенного остатка. Суммы
 * округляются HALF_UP до копеек — так же, как в процедурном ядре Python.
 */
@Service
public class BillingService {

    /** Точность денежных величин (копейки). */
    public static final int MONEY_SCALE = 2;

    /** Округление денежных величин. */
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /** Дневная ставка пени за первые 30 дней просрочки. */
    public static final BigDecimal DAILY_BASE_RATE = new BigDecimal("0.001");

    /** Дневная ставка пени за каждый день свыше 30. */
    public static final BigDecimal DAILY_EXTRA_RATE = new BigDecimal("0.002");

    /** Количество дней по базовой ставке. */
    public static final int BASE_DAYS = 30;

    /** Максимальная доля пени от непогашенного остатка. */
    public static final BigDecimal CAP_RATE = new BigDecimal("0.25");

    /**
     * Рассчитать пеню по непогашенному остатку.
     *
     * @param debt        непогашенный остаток
     * @param overdueDays количество дней просрочки
     * @return результат расчёта пени
     */
    public PenaltyResult calculatePenalty(BigDecimal debt, long overdueDays) {
        if (debt.signum() <= 0 || overdueDays <= 0) {
            return new PenaltyResult(money(BigDecimal.ZERO), false, new BigDecimal("0.00"));
        }

        long baseDays = Math.min(overdueDays, BASE_DAYS);
        long extraDays = Math.max(0L, overdueDays - BASE_DAYS);
        BigDecimal rawRate = DAILY_BASE_RATE
                .multiply(BigDecimal.valueOf(baseDays))
                .add(DAILY_EXTRA_RATE.multiply(BigDecimal.valueOf(extraDays)));
        BigDecimal rawPenalty = debt.multiply(rawRate);
        BigDecimal cap = debt.multiply(CAP_RATE);

        BigDecimal penalty;
        boolean capped;
        if (rawPenalty.compareTo(cap) > 0) {
            penalty = money(cap);
            capped = true;
        } else {
            penalty = money(rawPenalty);
            capped = false;
        }

        BigDecimal rate = penalty
                .divide(debt, 10, MONEY_ROUNDING)
                .multiply(BigDecimal.valueOf(100))
                .setScale(MONEY_SCALE, MONEY_ROUNDING);
        return new PenaltyResult(penalty, capped, rate);
    }

    /**
     * Пересчитать просрочки и пеню на контрольную дату.
     *
     * <p>Если передан {@code contracts} (не {@code null}), агрегат включает
     * каждый договор, даже без задолженности, — это делает результат
     * детерминированным и совместимым с Python-эталоном.
     *
     * @param payments        все платежи
     * @param calculationDate контрольная дата
     * @param contracts       опциональный список договоров для полного агрегата
     * @return детализация по платежам и агрегат по договорам
     */
    public DebtReport calculatePenaltiesAndDebt(
            List<Payment> payments, LocalDate calculationDate, List<LeaseContract> contracts) {
        List<PaymentDetail> details = new ArrayList<>();
        Map<Long, ContractDebt> byContract = new TreeMap<>();

        if (contracts != null) {
            for (LeaseContract contract : contracts) {
                byContract.put(
                        contract.getId(),
                        new ContractDebt(contract.getId(), BigDecimal.ZERO, BigDecimal.ZERO, 0));
            }
        }

        for (Payment payment : payments) {
            if (payment.getStatus() == PaymentStatus.PAID) {
                continue;
            }
            if (!payment.getDueDate().isBefore(calculationDate)) {
                continue;
            }

            long overdueDays = ChronoUnit.DAYS.between(payment.getDueDate(), calculationDate);
            BigDecimal debt = money(payment.getAmountDue().subtract(payment.getPaidAmount()));
            PenaltyResult penalty = calculatePenalty(debt, overdueDays);

            details.add(new PaymentDetail(
                    payment.getId(),
                    payment.getContract().getId(),
                    payment.getPeriodNumber(),
                    payment.getDueDate(),
                    overdueDays,
                    debt,
                    penalty.penalty(),
                    penalty.rate(),
                    penalty.capped(),
                    PaymentStatus.OVERDUE));

            ContractDebt aggregate = byContract.computeIfAbsent(
                    payment.getContract().getId(),
                    id -> new ContractDebt(id, BigDecimal.ZERO, BigDecimal.ZERO, 0));
            aggregate.add(debt, penalty.penalty());
        }

        List<ContractDebt> contractsAggregate = new ArrayList<>();
        BigDecimal totalDebt = BigDecimal.ZERO;
        BigDecimal totalPenalty = BigDecimal.ZERO;
        for (Map.Entry<Long, ContractDebt> entry : byContract.entrySet()) {
            ContractDebt aggregate = entry.getValue();
            ContractDebt rounded = new ContractDebt(
                    aggregate.contractId(),
                    money(aggregate.debt()),
                    money(aggregate.penalty()),
                    aggregate.overduePayments());
            totalDebt = totalDebt.add(rounded.debt());
            totalPenalty = totalPenalty.add(rounded.penalty());
            contractsAggregate.add(rounded);
        }

        details.sort((left, right) -> {
            int byContractId = Long.compare(left.contractId(), right.contractId());
            if (byContractId != 0) {
                return byContractId;
            }
            return Long.compare(left.paymentId(), right.paymentId());
        });

        return new DebtReport(
                calculationDate,
                details,
                contractsAggregate,
                money(totalDebt),
                money(totalPenalty));
    }

    /**
     * Зарегистрировать (возможно частичную) оплату по платежу.
     *
     * <p>Метод изменяет переданную сущность и возвращает её же для удобства
     * использования в транзакционном сервисе.
     *
     * @param payment  запись графика платежей
     * @param amount   сумма к зачислению
     * @param paidDate дата фактической оплаты
     * @return тот же инстанс с обновлёнными полями
     */
    public Payment applyPayment(Payment payment, BigDecimal amount, LocalDate paidDate) {
        BigDecimal paidTotal = money(payment.getPaidAmount().add(amount));
        payment.setPaidAmount(paidTotal);
        payment.setPaidDate(paidDate);
        payment.setStatus(
                paidTotal.compareTo(payment.getAmountDue()) >= 0
                        ? PaymentStatus.PAID
                        : PaymentStatus.PARTIAL);
        return payment;
    }

    /**
     * Свести группировку по договору в стабильном порядке идентификаторов.
     *
     * @param byContract неупорядоченная агрегация
     * @return список агрегатов по возрастанию {@code contract_id}
     */
    public List<ContractDebt> aggregateRows(Map<Long, ContractDebt> byContract) {
        return new ArrayList<>(new LinkedHashMap<>(new TreeMap<>(byContract)).values());
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Результат расчёта пени.
     *
     * @param penalty сумма пени
     * @param capped  сработал ли лимит 25%
     * @param rate    эффективная ставка в процентах от долга
     */
    public record PenaltyResult(BigDecimal penalty, boolean capped, BigDecimal rate) {
    }

    /**
     * Детализация по просроченному платежу.
     *
     * @param paymentId    идентификатор платежа
     * @param contractId   идентификатор договора
     * @param periodNumber номер периода
     * @param dueDate      крайняя дата оплаты
     * @param overdueDays  количество дней просрочки
     * @param debt         непогашенный остаток
     * @param penalty      начисленная пеня
     * @param rate         эффективная ставка пени, %
     * @param capped       сработал ли лимит
     * @param status       итоговый статус платежа ({@code OVERDUE})
     */
    public record PaymentDetail(
            Long paymentId,
            Long contractId,
            Integer periodNumber,
            LocalDate dueDate,
            long overdueDays,
            BigDecimal debt,
            BigDecimal penalty,
            BigDecimal rate,
            boolean capped,
            PaymentStatus status) {
    }

    /**
     * Агрегат задолженности по договору (изменяемый аккумулятор).
     */
    public static final class ContractDebt {

        private final Long contractId;
        private BigDecimal debt;
        private BigDecimal penalty;
        private int overduePayments;

        /**
         * Создать аккумулятор.
         *
         * @param contractId      идентификатор договора
         * @param debt            начальный долг
         * @param penalty         начальная пеня
         * @param overduePayments количество просроченных платежей
         */
        public ContractDebt(
                Long contractId, BigDecimal debt, BigDecimal penalty, int overduePayments) {
            this.contractId = contractId;
            this.debt = debt;
            this.penalty = penalty;
            this.overduePayments = overduePayments;
        }

        /**
         * Добавить задолженность и пеню по очередному платежу.
         *
         * @param amount  непогашенный остаток платежа
         * @param penaltyAmount пеня по платежу
         */
        public void add(BigDecimal amount, BigDecimal penaltyAmount) {
            this.debt = this.debt.add(amount);
            this.penalty = this.penalty.add(penaltyAmount);
            this.overduePayments += 1;
        }

        /** @return идентификатор договора */
        public Long contractId() {
            return contractId;
        }

        /** @return накопленный долг */
        public BigDecimal debt() {
            return debt;
        }

        /** @return накопленная пеня */
        public BigDecimal penalty() {
            return penalty;
        }

        /** @return количество просроченных платежей */
        public int overduePayments() {
            return overduePayments;
        }
    }

    /**
     * Итоговый отчёт по задолженности.
     *
     * @param calculationDate контрольная дата
     * @param payments        детализация по платежам
     * @param contracts       агрегат по договорам
     * @param totalDebt       суммарный долг
     * @param totalPenalty    суммарная пеня
     */
    public record DebtReport(
            LocalDate calculationDate,
            List<PaymentDetail> payments,
            List<ContractDebt> contracts,
            BigDecimal totalDebt,
            BigDecimal totalPenalty) {
    }
}
