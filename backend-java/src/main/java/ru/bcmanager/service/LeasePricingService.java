package ru.bcmanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import ru.bcmanager.domain.premises.Premises;

/**
 * Расчёт стоимости аренды и генерация графика платежей (Plan.md, раздел 4.1,
 * Алгоритм 3).
 *
 * <p>Сервис опирается на полиморфную иерархию {@link Premises}: типизированная
 * ставка и OpEx вычисляются наследниками, поэтому сервис не содержит
 * {@code switch} по типу помещения.
 *
 * <p>Порядок округления повторяет процедурное ядро Python: сначала
 * округляется {@code net} (HALF_UP до копеек), затем НДС считается от уже
 * округлённого {@code net}, затем {@code total = net + vat}. Это гарантирует
 * {@code net + vat == total} и по-копеечную идентичность Java и Python.
 */
@Service
public class LeasePricingService {

    /** Точность денежных величин (копейки). */
    public static final int MONEY_SCALE = 2;

    /** Округление денежных величин. */
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /** Ставка НДС по умолчанию. */
    public static final BigDecimal DEFAULT_VAT_RATE = new BigDecimal("0.20");

    /** День месяца, на который приходится срок оплаты со 2-го периода. */
    public static final int DUE_DAY_OF_MONTH = 5;

    /** Порог полных месяцев для скидки 5%. */
    public static final int DISCOUNT_MIN_MONTHS = 6;

    /** Порог полных месяцев для скидки 10%. */
    public static final int DISCOUNT_MAX_MONTHS = 12;

    /**
     * Рассчитать помесячную ренту без графика платежей.
     *
     * @param premises  помещение (тип определяет коэффициенты)
     * @param startDate дата начала аренды
     * @param endDate   дата окончания аренды (включительно)
     * @param vatRate   ставка НДС, напр. {@code 0.20}
     * @return детализация расчёта
     */
    public RentCalculation calculate(
            Premises premises, LocalDate startDate, LocalDate endDate, BigDecimal vatRate) {
        BigDecimal base = premises.getAreaSqm().multiply(premises.getBaseRatePerSqm());
        BigDecimal typed = premises.calculateBaseMonthlyCost();
        BigDecimal opex = premises.calculateOpexCost();

        int months = countFullMonths(startDate, endDate);
        BigDecimal discount = discountPercent(months);
        BigDecimal discountFactor = BigDecimal.ONE.subtract(
                discount.divide(BigDecimal.valueOf(100), 6, MONEY_ROUNDING));

        BigDecimal net = typed.add(opex).multiply(discountFactor);
        net = money(net);
        BigDecimal vat = money(net.multiply(vatRate));
        BigDecimal total = money(net.add(vat));

        return new RentCalculation(
                months,
                money(base),
                typed.setScale(MONEY_SCALE, MONEY_ROUNDING),
                opex.setScale(MONEY_SCALE, MONEY_ROUNDING),
                discount,
                net,
                vat,
                total);
    }

    /**
     * Рассчитать ренту и сгенерировать график платежей.
     *
     * @param premises  помещение
     * @param startDate дата начала аренды
     * @param endDate   дата окончания аренды (включительно)
     * @param vatRate   ставка НДС
     * @return расчёт с заполненным списком платежей
     */
    public RentCalculation calculateWithSchedule(
            Premises premises, LocalDate startDate, LocalDate endDate, BigDecimal vatRate) {
        RentCalculation calculation = calculate(premises, startDate, endDate, vatRate);
        List<ScheduleEntry> schedule =
                generatePaymentSchedule(startDate, calculation.months(), calculation.totalMonthly());
        return calculation.withSchedule(schedule);
    }

    /**
     * Сгенерировать месячный график платежей.
     *
     * <p>Первый период оплачивается в дату начала аренды, каждый следующий —
     * 5-го числа соответствующего месяца (день ограничивается последним днём
     * месяца). Все периоды имеют одинаковую сумму.
     *
     * @param startDate дата начала аренды
     * @param months    количество периодов
     * @param amountDue сумма к оплате за период (с НДС)
     * @return список записей графика
     */
    public List<ScheduleEntry> generatePaymentSchedule(
            LocalDate startDate, int months, BigDecimal amountDue) {
        List<ScheduleEntry> schedule = new ArrayList<>(Math.max(months, 0));
        for (int period = 1; period <= months; period++) {
            LocalDate dueDate;
            if (period == 1) {
                dueDate = startDate;
            } else {
                YearMonth shifted = YearMonth.from(startDate).plusMonths(period - 1L);
                int day = Math.min(DUE_DAY_OF_MONTH, shifted.lengthOfMonth());
                dueDate = shifted.atDay(day);
            }
            schedule.add(new ScheduleEntry(period, dueDate, amountDue));
        }
        return schedule;
    }

    /**
     * Скидка за длительность аренды в процентах.
     *
     * @param months количество полных месяцев
     * @return {@code 0}, {@code 5} или {@code 10}
     */
    public BigDecimal discountPercent(int months) {
        if (months >= DISCOUNT_MAX_MONTHS) {
            return new BigDecimal("10.00");
        }
        if (months >= DISCOUNT_MIN_MONTHS) {
            return new BigDecimal("5.00");
        }
        return new BigDecimal("0.00");
    }

    /**
     * Количество полных месяцев в интервале {@code [start, end]}.
     *
     * <p>Алгоритм повторяет Python-реализацию: разница по месяцам плюс один
     * месяц, если день окончания не меньше дня начала. Так
     * {@code 2026-03-01..2027-02-28} даёт 12 месяцев, а
     * {@code 2026-03-01..2026-03-31} — 13.
     *
     * @param start дата начала
     * @param end   дата окончания (включительно)
     * @return неотрицательное количество месяцев
     */
    public int countFullMonths(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return 0;
        }
        int months = (end.getYear() - start.getYear()) * 12 + (end.getMonthValue() - start.getMonthValue());
        if (end.getDayOfMonth() >= start.getDayOfMonth()) {
            months += 1;
        }
        return Math.max(0, months);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Детализация расчёта аренды (аналог dict из Python-ядра).
     *
     * @param months          количество полных месяцев
     * @param baseMonthly     базовая ставка до коэффициентов
     * @param typedMonthly    ставка после коэффициента типа и надбавки
     * @param opexMonthly     эксплуатационный сбор
     * @param discountPercent скидка за длительность, %
     * @param netMonthly      сумма до НДС
     * @param vatAmount       НДС
     * @param totalMonthly    итоговая сумма с НДС
     * @param schedule        график платежей (может быть {@code null})
     */
    public record RentCalculation(
            int months,
            BigDecimal baseMonthly,
            BigDecimal typedMonthly,
            BigDecimal opexMonthly,
            BigDecimal discountPercent,
            BigDecimal netMonthly,
            BigDecimal vatAmount,
            BigDecimal totalMonthly,
            List<ScheduleEntry> schedule) {

        /**
         * Расчёт без графика платежей.
         *
         * @param months          количество полных месяцев
         * @param baseMonthly     базовая ставка до коэффициентов
         * @param typedMonthly    ставка после коэффициента типа и надбавки
         * @param opexMonthly     эксплуатационный сбор
         * @param discountPercent скидка за длительность, %
         * @param netMonthly      сумма до НДС
         * @param vatAmount       НДС
         * @param totalMonthly    итоговая сумма с НДС
         */
        public RentCalculation(
                int months,
                BigDecimal baseMonthly,
                BigDecimal typedMonthly,
                BigDecimal opexMonthly,
                BigDecimal discountPercent,
                BigDecimal netMonthly,
                BigDecimal vatAmount,
                BigDecimal totalMonthly) {
            this(
                    months,
                    baseMonthly,
                    typedMonthly,
                    opexMonthly,
                    discountPercent,
                    netMonthly,
                    vatAmount,
                    totalMonthly,
                    List.of());
        }

        private RentCalculation withSchedule(List<ScheduleEntry> entries) {
            return new RentCalculation(
                    months,
                    baseMonthly,
                    typedMonthly,
                    opexMonthly,
                    discountPercent,
                    netMonthly,
                    vatAmount,
                    totalMonthly,
                    List.copyOf(entries));
        }
    }

    /**
     * Запись графика платежей.
     *
     * @param periodNumber номер периода с 1
     * @param dueDate      крайняя дата оплаты
     * @param amountDue    сумма к оплате
     */
    public record ScheduleEntry(int periodNumber, LocalDate dueDate, BigDecimal amountDue) {
    }
}
