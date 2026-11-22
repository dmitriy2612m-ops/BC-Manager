package ru.bcmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.enums.ContractStatus;
import ru.bcmanager.domain.enums.MaintenanceCategory;
import ru.bcmanager.domain.enums.MaintenancePriority;
import ru.bcmanager.domain.enums.PaymentStatus;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.domain.premises.ParkingSpot;
import ru.bcmanager.domain.premises.Premises;

/**
 * Unit-тесты сервисов Сессии 5 (границы алгоритмов и локальные сценарии).
 */
class ServiceUnitTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static Premises office(String code, String area, String rate, boolean furniture) {
        return new OfficeSpace(
                code, "Корпус А", 3, bd(area), bd(rate), PremisesStatus.AVAILABLE, furniture);
    }

    // ------------------------------------------------------------------ //
    // LeasePricingService
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Скидка: границы 5/6/11/12 полных месяцев")
    void discountBoundaries() {
        LeasePricingService service = new LeasePricingService();
        assertThat(service.countFullMonths(date("2026-03-01"), date("2026-07-31"))).isEqualTo(5);
        assertThat(service.countFullMonths(date("2026-03-01"), date("2026-08-31"))).isEqualTo(6);
        assertThat(service.countFullMonths(date("2026-03-01"), date("2027-01-31"))).isEqualTo(11);
        assertThat(service.countFullMonths(date("2026-03-01"), date("2027-02-28"))).isEqualTo(12);

        assertThat(service.discountPercent(5)).isEqualByComparingTo("0.00");
        assertThat(service.discountPercent(6)).isEqualByComparingTo("5.00");
        assertThat(service.discountPercent(11)).isEqualByComparingTo("5.00");
        assertThat(service.discountPercent(12)).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("Расчёт офиса с мебелью на 12 месяцев: net + vat == total")
    void pricingInvariant() {
        LeasePricingService service = new LeasePricingService();
        LeasePricingService.RentCalculation result = service.calculate(
                office("A-302", "45.00", "1150.00", true),
                date("2026-03-01"),
                date("2027-02-28"),
                LeasePricingService.DEFAULT_VAT_RATE);

        assertThat(result.months()).isEqualTo(12);
        assertThat(result.typedMonthly()).isEqualByComparingTo("56925.00");
        assertThat(result.opexMonthly()).isEqualByComparingTo("6750.00");
        assertThat(result.netMonthly()).isEqualByComparingTo("57307.50");
        assertThat(result.vatAmount()).isEqualByComparingTo("11461.50");
        assertThat(result.totalMonthly()).isEqualByComparingTo("68769.00");
        assertThat(result.netMonthly().add(result.vatAmount())).isEqualByComparingTo(result.totalMonthly());

        List<LeasePricingService.ScheduleEntry> schedule = service.generatePaymentSchedule(
                date("2026-03-01"), 12, result.totalMonthly());
        assertThat(schedule).hasSize(12);
        assertThat(schedule.get(0).dueDate()).isEqualTo(date("2026-03-01"));
        assertThat(schedule.get(1).dueDate()).isEqualTo(date("2026-04-05"));
        assertThat(schedule.get(11).dueDate()).isEqualTo(date("2027-02-05"));
    }

    // ------------------------------------------------------------------ //
    // BillingService
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Пени: 0 дней, 20 дней и лимит 25% на 300 днях")
    void penaltyRules() {
        BillingService service = new BillingService();

        assertThat(service.calculatePenalty(bd("100000.00"), 0).penalty())
                .isEqualByComparingTo("0.00");
        assertThat(service.calculatePenalty(bd("100000.00"), 20).penalty())
                .isEqualByComparingTo("2000.00");
        assertThat(service.calculatePenalty(bd("100000.00"), 40).penalty())
                .isEqualByComparingTo("5000.00");

        BillingService.PenaltyResult capped = service.calculatePenalty(bd("100000.00"), 300);
        assertThat(capped.penalty()).isEqualByComparingTo("25000.00");
        assertThat(capped.capped()).isTrue();
    }

    @Test
    @DisplayName("Фиксация частичной и полной оплаты")
    void applyPayment() {
        BillingService service = new BillingService();
        Premises premises = office("A-101", "30.00", "1000.00", false);
        premises.setId(10L);
        Tenant tenant = new Tenant("ООО Оплата", "7701234567", "Иванов", "+7", "a@b.ru");
        tenant.setId(10L);
        LeaseContract contract = new LeaseContract(
                "BC-TEST-0001",
                premises,
                tenant,
                date("2026-01-01"),
                date("2026-12-31"),
                bd("1000.00"),
                ContractStatus.ACTIVE);
        contract.setId(10L);
        Payment payment = new Payment(contract, 1, date("2026-01-05"), bd("1000.00"));
        payment.setId(1L);

        service.applyPayment(payment, bd("400.00"), date("2026-01-06"));
        assertThat(payment.getPaidAmount()).isEqualByComparingTo("400.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL);

        service.applyPayment(payment, bd("600.00"), date("2026-01-07"));
        assertThat(payment.getPaidAmount()).isEqualByComparingTo("1000.00");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PAID);
    }

    // ------------------------------------------------------------------ //
    // SpaceMatchingService
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Конфликт: стык дат не считается пересечением, DRAFT участвует")
    void conflictEdgeCases() {
        SpaceMatchingService service = new SpaceMatchingService(new LeasePricingService());
        Premises premises = office("A-201", "62.50", "1200.00", true);
        premises.setId(1L);
        Tenant tenant = new Tenant("ООО", "7701234567", "Иванов", "+7", "a@b.ru");
        tenant.setId(1L);
        LeaseContract draft = new LeaseContract(
                "BC-2026-0007",
                premises,
                tenant,
                date("2026-03-01"),
                date("2027-02-28"),
                bd("100000.00"),
                ContractStatus.DRAFT);
        draft.setId(7L);

        SpaceMatchingService.ConflictResult adjacent = service.checkLeaseConflict(
                List.of(draft), 1L, date("2026-02-28"), date("2026-02-28"), null);
        assertThat(adjacent.hasConflict()).isFalse();

        SpaceMatchingService.ConflictResult overlapping = service.checkLeaseConflict(
                List.of(draft), 1L, date("2026-02-28"), date("2026-03-01"), null);
        assertThat(overlapping.hasConflict()).isTrue();
        assertThat(overlapping.conflictingContractId()).isEqualTo(7L);

        SpaceMatchingService.ConflictResult excluded = service.checkLeaseConflict(
                List.of(draft), 1L, date("2026-03-01"), date("2026-03-31"), 7L);
        assertThat(excluded.hasConflict()).isFalse();
    }

    // ------------------------------------------------------------------ //
    // MaintenanceQueueService
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Очередь: строгие границы SLA (ровно 48ч не эскалирует)")
    void queueBoundaries() {
        MaintenanceQueueService service = new MaintenanceQueueService();
        Premises premises = office("A-301", "82.50", "1250.00", false);

        MaintenanceRequest fresh = request(premises, MaintenancePriority.MEDIUM, "2026-01-13T12:00:00", 1L);
        MaintenanceRequest escalated = request(premises, MaintenancePriority.MEDIUM, "2026-01-13T11:00:00", 2L);
        LocalDateTime now = LocalDateTime.parse("2026-01-15T12:00:00");

        List<MaintenanceQueueService.QueueEntry> queue = service.prioritize(List.of(fresh, escalated), now);
        assertThat(queue).hasSize(2);
        assertThat(queue.get(0).request().getId()).isEqualTo(2L);
        assertThat(queue.get(0).effectiveWeight()).isEqualTo(2);
        assertThat(queue.get(1).effectiveWeight()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ //
    // AnalyticsService
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Аналитика: пустой бизнес-центр даёт нули без деления на ноль")
    void analyticsEmptyPremises() {
        AnalyticsService service = new AnalyticsService();
        AnalyticsService.AnalyticsSummary summary = service.compute(
                List.of(), List.of(), List.of(), List.of(), date("2026-01-15"));

        assertThat(summary.totalAreaSqm()).isEqualByComparingTo("0.00");
        assertThat(summary.occupancyPercent()).isEqualByComparingTo("0.00");
        assertThat(summary.averageYieldPerSqm()).isEqualByComparingTo("0.00");
        assertThat(summary.byType()).isEmpty();
        assertThat(summary.byFloor()).isEmpty();
    }

    @Test
    @DisplayName("Аналитика: паркинг на -1 этаже попадает в by_floor")
    void analyticsNegativeFloor() {
        AnalyticsService service = new AnalyticsService();
        Premises parking = new ParkingSpot(
                "P-01", "Паркинг", -1, bd("14.00"), bd("900.00"), PremisesStatus.AVAILABLE, true);
        parking.setId(1L);

        AnalyticsService.AnalyticsSummary summary = service.compute(
                List.of(parking), List.of(), List.of(), List.of(), date("2026-01-15"));

        assertThat(summary.byFloor()).hasSize(1);
        assertThat(summary.byFloor().get(0).floor()).isEqualTo(-1);
        assertThat(summary.byType().get(0).type()).isEqualTo("PARKING");
    }

    @Test
    @DisplayName("CSV-экспорт помещений содержит шапку и строки через ';'")
    void premisesCsv() {
        AnalyticsService service = new AnalyticsService();
        Premises premises = office("A-301", "82.50", "1250.00", false);
        premises.setId(1L);

        String csv = service.premisesCsv(List.of(premises), true);

        assertThat(csv).startsWith(AnalyticsService.PREMISES_CSV_HEADER);
        assertThat(csv).contains("A-301");
        assertThat(csv).contains("OFFICE");
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }

    private static MaintenanceRequest request(
            Premises premises, MaintenancePriority priority, String createdAt, Long id) {
        MaintenanceRequest request = new MaintenanceRequest(
                premises, null, "Заявка", "Описание", MaintenanceCategory.GENERAL, priority);
        request.setId(id);
        request.setCreatedAt(LocalDateTime.parse(createdAt));
        return request;
    }
}
