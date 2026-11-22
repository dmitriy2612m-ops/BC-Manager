package ru.bcmanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Disabled;
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
import ru.bcmanager.domain.premises.ConferenceRoom;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.domain.premises.ParkingSpot;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.domain.premises.RetailSpace;

/**
 * Бенчмарк Java-алгоритмов для сопоставления с Python-ядром (Сессия 5).
 *
 * <p>По умолчанию тест отключён ({@link Disabled}), чтобы не удлинять обычный
 * прогон {@code mvn test}. Запуск:
 * <pre>mvn test -Dtest=BenchmarkTest -Dsurefire.failIfNoSpecifiedTests=false</pre>
 * (для исполнения нужно временно убрать {@code @Disabled}).
 */
class BenchmarkTest {

    private static final int REPEATS = 3;

    private static final LeasePricingService PRICING = new LeasePricingService();

    private static final SpaceMatchingService MATCHING = new SpaceMatchingService(PRICING);

    private static final BillingService BILLING = new BillingService();

    private static final MaintenanceQueueService QUEUE = new MaintenanceQueueService();

    private static final AnalyticsService ANALYTICS = new AnalyticsService();

    @Disabled("Ручной бенчмарк: запускается отдельно через -Dtest=BenchmarkTest")
    @Test
    void benchmark() {
        for (int size : new int[] {1000, 10000}) {
            SyntheticDataset data = build(size, 42L);
            long conflicts = Long.MAX_VALUE;
            long matching = Long.MAX_VALUE;
            long pricing = Long.MAX_VALUE;
            long penalties = Long.MAX_VALUE;
            long queue = Long.MAX_VALUE;
            long analytics = Long.MAX_VALUE;

            for (int repeat = 0; repeat < REPEATS; repeat++) {
                long started = System.nanoTime();
                MATCHING.checkLeaseConflict(
                        data.contracts(),
                        data.premises().get(0).getId(),
                        LocalDate.parse("2025-06-01"),
                        LocalDate.parse("2026-02-01"),
                        null);
                conflicts = Math.min(conflicts, System.nanoTime() - started);

                started = System.nanoTime();
                MATCHING.matchAndRank(
                        data.premises(),
                        data.contracts(),
                        new SpaceMatchingService.MatchRequest(
                                ru.bcmanager.domain.enums.PremisesType.OFFICE,
                                new BigDecimal("80.00"),
                                new BigDecimal("40.00"),
                                new BigDecimal("140.00"),
                                new BigDecimal("500000.00"),
                                LocalDate.parse("2026-03-01"),
                                LocalDate.parse("2026-12-31")),
                        LeasePricingService.DEFAULT_VAT_RATE);
                matching = Math.min(matching, System.nanoTime() - started);

                started = System.nanoTime();
                PRICING.calculateWithSchedule(
                        data.premises().get(0),
                        LocalDate.parse("2026-03-01"),
                        LocalDate.parse("2027-02-28"),
                        LeasePricingService.DEFAULT_VAT_RATE);
                pricing = Math.min(pricing, System.nanoTime() - started);

                started = System.nanoTime();
                BILLING.calculatePenaltiesAndDebt(
                        data.payments(), data.asOf(), data.contracts());
                penalties = Math.min(penalties, System.nanoTime() - started);

                started = System.nanoTime();
                QUEUE.prioritize(data.requests(), data.now());
                queue = Math.min(queue, System.nanoTime() - started);

                started = System.nanoTime();
                ANALYTICS.compute(
                        data.premises(), data.contracts(), data.payments(), data.requests(), data.asOf());
                analytics = Math.min(analytics, System.nanoTime() - started);
            }

            System.out.printf(
                    "N=%d | conflicts=%.3f ms | matching=%.3f ms | pricing=%.3f ms | "
                            + "penalties=%.3f ms | queue=%.3f ms | analytics=%.3f ms%n",
                    size,
                    conflicts / 1_000_000.0,
                    matching / 1_000_000.0,
                    pricing / 1_000_000.0,
                    penalties / 1_000_000.0,
                    queue / 1_000_000.0,
                    analytics / 1_000_000.0);
        }
    }

    private static SyntheticDataset build(int size, long seed) {
        Random random = new Random(seed);
        List<Premises> premises = new ArrayList<>(size);
        List<LeaseContract> contracts = new ArrayList<>(size / 2);
        Tenant tenant = new Tenant("Synthetic", "7701234567", "Test", "+7", "s@t.ru");
        tenant.setId(1L);
        LocalDate baseDay = LocalDate.parse("2025-01-01");

        for (int index = 1; index <= size; index++) {
            String type = switch (index % 4) {
                case 0 -> "OFFICE";
                case 1 -> "RETAIL";
                case 2 -> "CONFERENCE";
                default -> "PARKING";
            };
            BigDecimal area = BigDecimal.valueOf(12.0 + random.nextDouble() * 128.0)
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal rate = BigDecimal.valueOf(700.0 + random.nextDouble() * 1900.0)
                    .setScale(2, RoundingMode.HALF_UP);
            PremisesStatus status = index % 2 == 0 ? PremisesStatus.RENTED : PremisesStatus.AVAILABLE;
            Premises item = switch (type) {
                case "OFFICE" -> new OfficeSpace("S-" + index, "Корпус А", index % 5, area, rate, status, index % 2 == 1);
                case "RETAIL" -> new RetailSpace("S-" + index, "Корпус Б", index % 5, area, rate, status, index % 3 == 0);
                case "CONFERENCE" -> new ConferenceRoom("S-" + index, "Корпус В", index % 5, area, rate, status, index % 40);
                default -> new ParkingSpot("S-" + index, "Паркинг", -1, area, rate, status, index % 2 == 1);
            };
            item.setId((long) index);
            premises.add(item);

            if (index % 2 == 0) {
                LocalDate start = baseDay.plusDays(random.nextInt(300));
                LeaseContract contract = new LeaseContract(
                        "SYN-" + index,
                        item,
                        tenant,
                        start,
                        start.plusMonths(12).minusDays(1),
                        area.multiply(rate).setScale(2, RoundingMode.HALF_UP),
                        ContractStatus.ACTIVE);
                contract.setId((long) (index / 2));
                contracts.add(contract);
            }
        }

        List<Payment> payments = new ArrayList<>(contracts.size());
        for (LeaseContract contract : contracts) {
            Payment payment = new Payment(
                    contract, 1, contract.getStartDate(), contract.getMonthlyRent());
            payment.setId(contract.getId() * 10);
            payment.setStatus(PaymentStatus.PENDING);
            payments.add(payment);
        }

        List<MaintenanceRequest> requests = new ArrayList<>();
        MaintenancePriority[] priorities = MaintenancePriority.values();
        int requestCount = Math.max(10, size / 100);
        for (int index = 1; index <= requestCount; index++) {
            MaintenanceRequest request = new MaintenanceRequest(
                    premises.get(index % size),
                    null,
                    "Заявка " + index,
                    "Синтетическая заявка",
                    MaintenanceCategory.GENERAL,
                    priorities[index % priorities.length]);
            request.setId((long) index);
            request.setCreatedAt(LocalDateTime.parse("2026-01-01T00:00:00").plusHours(index));
            requests.add(request);
        }

        return new SyntheticDataset(
                premises,
                contracts,
                payments,
                requests,
                LocalDate.parse("2026-01-15"),
                LocalDateTime.parse("2026-01-15T12:00:00"));
    }

    private record SyntheticDataset(
            List<Premises> premises,
            List<LeaseContract> contracts,
            List<Payment> payments,
            List<MaintenanceRequest> requests,
            LocalDate asOf,
            LocalDateTime now) {
    }
}
