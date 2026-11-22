package ru.bcmanager.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.enums.ContractStatus;
import ru.bcmanager.domain.enums.MaintenanceCategory;
import ru.bcmanager.domain.enums.MaintenancePriority;
import ru.bcmanager.domain.enums.MaintenanceStatus;
import ru.bcmanager.domain.enums.PaymentStatus;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;
import ru.bcmanager.domain.premises.ConferenceRoom;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.domain.premises.ParkingSpot;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.domain.premises.RetailSpace;

/**
 * Parity-тест: Java-реализация алгоритмов 1–6 против
 * {@code shared-fixtures/expected_results.json}.
 *
 * <p>Тест загружает единый набор данных {@code test_dataset.json}, строит
 * доменные сущности Java (ту же форму, что даёт JPA) и сравнивает результат
 * сервисов с эталоном Python-ядра. Каждый денежный/процентный показатель
 * сравнивается как строка с двумя знаками, что исключает влияние масштаба
 * {@code BigDecimal}.
 */
class ParityTest {

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static JsonNode dataset;

    private static JsonNode expected;

    private static final LeasePricingService PRICING = new LeasePricingService();

    private static final SpaceMatchingService MATCHING = new SpaceMatchingService(PRICING);

    private static final BillingService BILLING = new BillingService();

    private static final MaintenanceQueueService QUEUE = new MaintenanceQueueService();

    private static final AnalyticsService ANALYTICS = new AnalyticsService();

    @BeforeAll
    static void loadFixtures() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Path root = Path.of("..", "shared-fixtures");
        dataset = mapper.readTree(Files.readString(root.resolve("test_dataset.json")));
        expected = mapper.readTree(Files.readString(root.resolve("expected_results.json")));
    }

    // --------------------------------------------------------------------- //
    // Fixture construction helpers
    // --------------------------------------------------------------------- //

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : new BigDecimal(value);
    }

    private static Long longOrNull(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : Long.valueOf(value);
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDate.parse(value);
    }

    private static LocalDateTime dateTime(JsonNode node, String field) {
        String value = text(node, field);
        return value == null ? null : LocalDateTime.parse(value, DATETIME);
    }

    private static Premises buildPremises(JsonNode node) {
        Long id = longOrNull(node, "id");
        String code = text(node, "code");
        String building = text(node, "building");
        Integer floor = node.get("floor").asInt();
        BigDecimal area = decimal(node, "area_sqm");
        BigDecimal rate = decimal(node, "base_rate_per_sqm");
        PremisesStatus status = PremisesStatus.valueOf(text(node, "status"));
        PremisesType type = PremisesType.valueOf(text(node, "type"));

        Premises premises;
        switch (type) {
            case OFFICE -> premises = new OfficeSpace(
                    code, building, floor, area, rate, status, node.path("has_furniture").asBoolean());
            case RETAIL -> premises = new RetailSpace(
                    code, building, floor, area, rate, status, node.path("has_showcase").asBoolean());
            case PARKING -> premises = new ParkingSpot(
                    code, building, floor, area, rate, status, node.path("is_covered").asBoolean());
            case CONFERENCE -> premises = new ConferenceRoom(
                    code,
                    building,
                    floor,
                    area,
                    rate,
                    status,
                    node.hasNonNull("capacity_people") ? node.get("capacity_people").asInt() : null);
            default -> throw new IllegalArgumentException("Unknown type " + type);
        }
        premises.setId(id);
        premises.setDescription(text(node, "description"));
        return premises;
    }

    private static List<Premises> buildPremisesList() {
        List<Premises> result = new ArrayList<>();
        for (JsonNode node : dataset.get("premises")) {
            result.add(buildPremises(node));
        }
        return result;
    }

    private static Map<Long, Tenant> buildTenants() {
        Map<Long, Tenant> tenants = new LinkedHashMap<>();
        for (JsonNode node : dataset.get("tenants")) {
            Tenant tenant = new Tenant(
                    text(node, "company_name"),
                    text(node, "inn"),
                    text(node, "contact_person"),
                    text(node, "phone"),
                    text(node, "email"));
            tenant.setId(longOrNull(node, "id"));
            tenant.setActive(node.path("is_active").asBoolean(true));
            tenants.put(tenant.getId(), tenant);
        }
        return tenants;
    }

    private static List<LeaseContract> buildContracts(List<Premises> premisesList, Map<Long, Tenant> tenants) {
        Map<Long, Premises> premisesById = new LinkedHashMap<>();
        for (Premises premises : premisesList) {
            premisesById.put(premises.getId(), premises);
        }
        List<LeaseContract> contracts = new ArrayList<>();
        for (JsonNode node : dataset.get("contracts")) {
            LeaseContract contract = new LeaseContract(
                    text(node, "contract_number"),
                    premisesById.get(longOrNull(node, "premises_id")),
                    tenants.get(longOrNull(node, "tenant_id")),
                    date(node, "start_date"),
                    date(node, "end_date"),
                    decimal(node, "monthly_rent"),
                    ContractStatus.valueOf(text(node, "status")));
            contract.setId(longOrNull(node, "id"));
            contract.setDiscountPercent(decimal(node, "discount_percent"));
            contract.setDepositAmount(decimal(node, "deposit_amount"));
            contracts.add(contract);
        }
        return contracts;
    }

    private static List<Payment> buildPayments(List<LeaseContract> contracts) {
        Map<Long, LeaseContract> contractsById = new LinkedHashMap<>();
        for (LeaseContract contract : contracts) {
            contractsById.put(contract.getId(), contract);
        }
        List<Payment> payments = new ArrayList<>();
        for (JsonNode node : dataset.get("payments")) {
            Payment payment = new Payment(
                    contractsById.get(longOrNull(node, "contract_id")),
                    node.get("period_number").asInt(),
                    date(node, "due_date"),
                    decimal(node, "amount_due"));
            payment.setId(longOrNull(node, "id"));
            payment.setPaidAmount(decimal(node, "paid_amount"));
            payment.setPaidDate(date(node, "paid_date"));
            payment.setPenaltyAmount(decimal(node, "penalty_amount"));
            payment.setStatus(PaymentStatus.valueOf(text(node, "status")));
            payments.add(payment);
        }
        return payments;
    }

    private static List<MaintenanceRequest> buildRequests(List<Premises> premisesList, Map<Long, Tenant> tenants) {
        Map<Long, Premises> premisesById = new LinkedHashMap<>();
        for (Premises premises : premisesList) {
            premisesById.put(premises.getId(), premises);
        }
        List<MaintenanceRequest> requests = new ArrayList<>();
        for (JsonNode node : dataset.get("maintenance_requests")) {
            MaintenanceRequest request = new MaintenanceRequest(
                    premisesById.get(longOrNull(node, "premises_id")),
                    tenants.get(longOrNull(node, "tenant_id")),
                    text(node, "title"),
                    text(node, "description"),
                    MaintenanceCategory.valueOf(text(node, "category")),
                    MaintenancePriority.valueOf(text(node, "priority")));
            request.setId(longOrNull(node, "id"));
            request.setStatus(MaintenanceStatus.valueOf(text(node, "status")));
            request.setCreatedAt(dateTime(node, "created_at"));
            request.setResolvedAt(dateTime(node, "resolved_at"));
            requests.add(request);
        }
        return requests;
    }

    private static String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static List<Premises> premiseFixtureList;

    private static Map<Long, Tenant> tenantFixtureMap;

    private static List<LeaseContract> contractFixtureList;

    private static List<Payment> paymentFixtureList;

    private static List<MaintenanceRequest> requestFixtureList;

    @BeforeAll
    static void buildDomain() {
        premiseFixtureList = buildPremisesList();
        tenantFixtureMap = buildTenants();
        contractFixtureList = buildContracts(premiseFixtureList, tenantFixtureMap);
        paymentFixtureList = buildPayments(contractFixtureList);
        requestFixtureList = buildRequests(premiseFixtureList, tenantFixtureMap);
    }

    // --------------------------------------------------------------------- //
    // Algorithm 1
    // --------------------------------------------------------------------- //

    @Test
    @DisplayName("Алгоритм 1: конфликты аренды совпадают с эталоном")
    void leaseConflictsMatchExpected() {
        JsonNode scenarios = dataset.get("scenarios").get("lease_conflict_checks");
        JsonNode expectedRows = expected.get("algorithm_1_lease_conflicts");

        for (int index = 0; index < scenarios.size(); index++) {
            JsonNode scenario = scenarios.get(index);
            JsonNode expectedRow = expectedRows.get(index);

            SpaceMatchingService.ConflictResult result = MATCHING.checkLeaseConflict(
                    contractFixtureList,
                    longOrNull(scenario, "premises_id"),
                    date(scenario, "start_date"),
                    date(scenario, "end_date"),
                    longOrNull(scenario, "exclude_contract_id"));

            assertThat(result.hasConflict())
                    .as("conflict for %s", text(scenario, "name"))
                    .isEqualTo(expectedRow.get("has_conflict").asBoolean());
            assertThat(result.conflictingContractId())
                    .as("contract id for %s", text(scenario, "name"))
                    .isEqualTo(longOrNull(expectedRow, "conflicting_contract_id"));
            assertThat(result.conflictingContractNumber())
                    .as("contract number for %s", text(scenario, "name"))
                    .isEqualTo(text(expectedRow, "conflicting_contract_number"));
        }
    }

    // --------------------------------------------------------------------- //
    // Algorithm 2
    // --------------------------------------------------------------------- //

    @Test
    @DisplayName("Алгоритм 2: умный подбор и ранжирование совпадают с эталоном")
    void matchingMatchesExpected() {
        JsonNode scenarios = dataset.get("scenarios").get("match_requests");
        JsonNode expectedRows = expected.get("algorithm_2_match_and_rank");

        for (int index = 0; index < scenarios.size(); index++) {
            JsonNode scenario = scenarios.get(index);
            JsonNode expectedRow = expectedRows.get(index);
            String type = text(scenario, "type");

            SpaceMatchingService.MatchRequest request = new SpaceMatchingService.MatchRequest(
                    type == null ? null : PremisesType.valueOf(type),
                    decimal(scenario, "target_area"),
                    decimal(scenario, "min_area"),
                    decimal(scenario, "max_area"),
                    decimal(scenario, "max_budget"),
                    date(scenario, "start_date"),
                    date(scenario, "end_date"));

            List<SpaceMatchingService.MatchCandidate> candidates =
                    MATCHING.matchAndRank(premiseFixtureList, contractFixtureList, request, LeasePricingService.DEFAULT_VAT_RATE);

            JsonNode expectedCandidates = expectedRow.get("candidates");
            assertThat(candidates.size())
                    .as("candidate count for %s", text(scenario, "name"))
                    .isEqualTo(expectedCandidates.size());

            for (int row = 0; row < candidates.size(); row++) {
                SpaceMatchingService.MatchCandidate candidate = candidates.get(row);
                JsonNode expectedCandidate = expectedCandidates.get(row);
                String context = text(scenario, "name") + "[" + row + "]";

                assertThat(candidate.premisesId())
                        .as("premises id %s", context)
                        .isEqualTo(longOrNull(expectedCandidate, "premises_id"));
                assertThat(candidate.code()).as("code %s", context).isEqualTo(text(expectedCandidate, "code"));
                assertThat(money(candidate.areaSqm()))
                        .as("area %s", context)
                        .isEqualTo(text(expectedCandidate, "area_sqm"));
                assertThat(money(candidate.estimatedMonthlyCost()))
                        .as("cost %s", context)
                        .isEqualTo(text(expectedCandidate, "estimated_monthly_cost"));
                assertThat(money(candidate.score()))
                        .as("score %s", context)
                        .isEqualTo(text(expectedCandidate, "score"));
                assertThat(candidate.rank())
                        .as("rank %s", context)
                        .isEqualTo(expectedCandidate.get("rank").asInt());
            }
        }
    }

    // --------------------------------------------------------------------- //
    // Algorithm 3
    // --------------------------------------------------------------------- //

    @Test
    @DisplayName("Алгоритм 3: расчёт аренды и график платежей совпадают с эталоном")
    void pricingMatchesExpected() {
        JsonNode scenarios = dataset.get("scenarios").get("pricing_requests");
        JsonNode expectedRows = expected.get("algorithm_3_rent_and_schedule");

        Map<Long, Premises> premisesById = new LinkedHashMap<>();
        for (Premises premises : premiseFixtureList) {
            premisesById.put(premises.getId(), premises);
        }

        for (int index = 0; index < scenarios.size(); index++) {
            JsonNode scenario = scenarios.get(index);
            JsonNode expectedRow = expectedRows.get(index);
            String context = text(scenario, "name");

            Long premisesId = longOrNull(scenario, "premises_id");
            LeasePricingService.RentCalculation calculation = PRICING.calculateWithSchedule(
                    premisesById.get(premisesId),
                    date(scenario, "start_date"),
                    date(scenario, "end_date"),
                    LeasePricingService.DEFAULT_VAT_RATE);

            assertThat(calculation.months()).as("months %s", context).isEqualTo(expectedRow.get("months").asInt());
            assertThat(money(calculation.baseMonthly()))
                    .as("base %s", context)
                    .isEqualTo(text(expectedRow, "base_monthly"));
            assertThat(money(calculation.typedMonthly()))
                    .as("typed %s", context)
                    .isEqualTo(text(expectedRow, "typed_monthly"));
            assertThat(money(calculation.opexMonthly()))
                    .as("opex %s", context)
                    .isEqualTo(text(expectedRow, "opex_monthly"));
            assertThat(money(calculation.discountPercent()))
                    .as("discount %s", context)
                    .isEqualTo(text(expectedRow, "discount_percent"));
            assertThat(money(calculation.netMonthly()))
                    .as("net %s", context)
                    .isEqualTo(text(expectedRow, "net_monthly"));
            assertThat(money(calculation.vatAmount()))
                    .as("vat %s", context)
                    .isEqualTo(text(expectedRow, "vat_amount"));
            assertThat(money(calculation.totalMonthly()))
                    .as("total %s", context)
                    .isEqualTo(text(expectedRow, "total_monthly"));
            assertThat(calculation.schedule().size())
                    .as("schedule size %s", context)
                    .isEqualTo(expectedRow.get("schedule_entries").asInt());

            JsonNode expectedSchedule = expectedRow.get("schedule");
            if (expectedSchedule != null) {
                for (int row = 0; row < expectedSchedule.size(); row++) {
                    JsonNode expectedEntry = expectedSchedule.get(row);
                    LeasePricingService.ScheduleEntry entry = calculation.schedule().get(row);
                    String entryContext = context + " schedule[" + row + "]";
                    assertThat(entry.periodNumber())
                            .as("period %s", entryContext)
                            .isEqualTo(expectedEntry.get("period_number").asInt());
                    assertThat(entry.dueDate().toString())
                            .as("due date %s", entryContext)
                            .isEqualTo(text(expectedEntry, "due_date"));
                    assertThat(money(entry.amountDue()))
                            .as("amount %s", entryContext)
                            .isEqualTo(text(expectedEntry, "amount_due"));
                }
            }
        }
    }

    // --------------------------------------------------------------------- //
    // Algorithm 4
    // --------------------------------------------------------------------- //

    @Test
    @DisplayName("Алгоритм 4: пени по сценариям совпадают с эталоном")
    void penaltyCasesMatchExpected() {
        JsonNode scenarios = dataset.get("scenarios").get("penalty_cases");
        JsonNode expectedRows = expected.get("algorithm_4_penalties");

        for (int index = 0; index < scenarios.size(); index++) {
            JsonNode scenario = scenarios.get(index);
            JsonNode expectedRow = expectedRows.get(index);
            String context = text(scenario, "name");

            long overdueDays = LocalDate.parse(text(scenario, "calculation_date"))
                    .toEpochDay()
                    - LocalDate.parse(text(scenario, "due_date")).toEpochDay();
            BigDecimal debt = decimal(scenario, "amount_due").subtract(decimal(scenario, "paid_amount"));
            BillingService.PenaltyResult result = BILLING.calculatePenalty(debt, overdueDays);

            assertThat(overdueDays).as("days %s", context).isEqualTo(expectedRow.get("overdue_days").asLong());
            assertThat(money(debt)).as("debt %s", context).isEqualTo(text(expectedRow, "debt"));
            assertThat(money(result.penalty()))
                    .as("penalty %s", context)
                    .isEqualTo(text(expectedRow, "penalty"));
            assertThat(result.capped()).as("capped %s", context).isEqualTo(expectedRow.get("capped").asBoolean());
        }
    }

    @Test
    @DisplayName("Алгоритм 4: агрегат задолженности по договорам совпадает с эталоном")
    void debtAggregateMatchesExpected() {
        JsonNode expectedRow = expected.get("algorithm_4_debt_aggregate");
        LocalDate calculationDate = LocalDate.parse(text(expectedRow, "calculation_date"));

        BillingService.DebtReport report =
                BILLING.calculatePenaltiesAndDebt(paymentFixtureList, calculationDate, contractFixtureList);

        JsonNode expectedContracts = expectedRow.get("contracts");
        assertThat(report.contracts().size())
                .as("contract rows")
                .isEqualTo(expectedContracts.size());

        for (int index = 0; index < expectedContracts.size(); index++) {
            JsonNode expectedContract = expectedContracts.get(index);
            BillingService.ContractDebt actual = report.contracts().get(index);
            String context = "contract " + expectedContract.get("contract_id").asLong();

            assertThat(actual.contractId())
                    .as("id %s", context)
                    .isEqualTo(expectedContract.get("contract_id").asLong());
            assertThat(money(actual.debt()))
                    .as("debt %s", context)
                    .isEqualTo(text(expectedContract, "debt"));
            assertThat(money(actual.penalty()))
                    .as("penalty %s", context)
                    .isEqualTo(text(expectedContract, "penalty"));
            assertThat(actual.overduePayments())
                    .as("overdue count %s", context)
                    .isEqualTo(expectedContract.get("overdue_payments").asInt());
        }

        assertThat(money(report.totalDebt()))
                .isEqualTo(text(expectedRow, "total_debt"));
        assertThat(money(report.totalPenalty()))
                .isEqualTo(text(expectedRow, "total_penalty"));
    }

    // --------------------------------------------------------------------- //
    // Algorithm 5
    // --------------------------------------------------------------------- //

    @Test
    @DisplayName("Алгоритм 5: приоритетная очередь совпадает с эталоном")
    void maintenanceQueueMatchesExpected() {
        JsonNode metadata = dataset.get("metadata");
        LocalDateTime now = LocalDateTime.parse(text(metadata, "maintenance_now"), DATETIME);
        JsonNode expectedRows = expected.get("algorithm_5_maintenance_queue");

        List<MaintenanceQueueService.QueueEntry> queue = QUEUE.prioritize(requestFixtureList, now);

        assertThat(queue.size())
                .as("queue size")
                .isEqualTo(expectedRows.size());

        for (int index = 0; index < expectedRows.size(); index++) {
            JsonNode expectedRow = expectedRows.get(index);
            MaintenanceQueueService.QueueEntry entry = queue.get(index);
            String context = "position " + (index + 1);

            assertThat(entry.request().getId())
                    .as("request id %s", context)
                    .isEqualTo(expectedRow.get("id").asLong());
            assertThat(entry.queuePosition())
                    .as("queue position %s", context)
                    .isEqualTo(expectedRow.get("queue_position").asInt());
            assertThat(entry.effectiveWeight())
                    .as("effective weight %s", context)
                    .isEqualTo(expectedRow.get("effective_weight").asInt());
            assertThat(entry.escalated())
                    .as("escalated %s", context)
                    .isEqualTo(expectedRow.get("escalated").asBoolean());
        }
    }

    @Test
    @DisplayName("Алгоритм 5: веса SLA по сценариям совпадают с эталоном")
    void slaWeightsMatchExpected() {
        JsonNode scenarios = dataset.get("scenarios").get("sla_cases");
        JsonNode expectedRows = expected.get("algorithm_5_sla_weights");

        for (int index = 0; index < scenarios.size(); index++) {
            JsonNode scenario = scenarios.get(index);
            JsonNode expectedRow = expectedRows.get(index);

            LocalDateTime created = LocalDateTime.parse(text(scenario, "created_at"), DATETIME);
            LocalDateTime now = LocalDateTime.parse(text(scenario, "now"), DATETIME);
            MaintenanceRequest request = new MaintenanceRequest(
                    premiseFixtureList.get(0),
                    null,
                    "SLA",
                    "SLA",
                    MaintenanceCategory.GENERAL,
                    MaintenancePriority.valueOf(text(scenario, "priority")));
            request.setCreatedAt(created);

            assertThat(request.calculateEffectivePriorityWeight(now))
                    .as("weight for %s", text(scenario, "name"))
                    .isEqualTo(expectedRow.get("effective_weight").asInt());
        }
    }

    // --------------------------------------------------------------------- //
    // Algorithm 6
    // --------------------------------------------------------------------- //

    @Test
    @DisplayName("Алгоритм 6: аналитика загруженности совпадает с эталоном")
    void analyticsMatchesExpected() {
        JsonNode expectedRow = expected.get("algorithm_6_analytics");
        LocalDate asOf = LocalDate.parse(text(expectedRow, "as_of"));

        AnalyticsService.AnalyticsSummary summary = ANALYTICS.compute(
                premiseFixtureList,
                contractFixtureList,
                paymentFixtureList,
                requestFixtureList,
                asOf);

        assertThat(money(summary.totalAreaSqm())).isEqualTo(text(expectedRow, "total_area_sqm"));
        assertThat(money(summary.occupiedAreaSqm())).isEqualTo(text(expectedRow, "occupied_area_sqm"));
        assertThat(money(summary.occupancyPercent())).isEqualTo(text(expectedRow, "occupancy_percent"));
        assertThat(money(summary.mrr())).isEqualTo(text(expectedRow, "mrr"));
        assertThat(money(summary.averageYieldPerSqm()))
                .isEqualTo(text(expectedRow, "average_yield_per_sqm"));
        assertThat(summary.openCriticalRequests())
                .isEqualTo(expectedRow.get("open_critical_requests").asInt());

        JsonNode expectedTypes = expectedRow.get("by_type");
        assertThat(summary.byType().size()).as("by_type size").isEqualTo(expectedTypes.size());
        for (int index = 0; index < expectedTypes.size(); index++) {
            JsonNode expectedType = expectedTypes.get(index);
            AnalyticsService.TypeOccupancy actual = summary.byType().get(index);
            assertThat(actual.type()).as("type name %d", index).isEqualTo(text(expectedType, "type"));
            assertThat(money(actual.totalAreaSqm()))
                    .as("type total %s", actual.type())
                    .isEqualTo(text(expectedType, "total_area_sqm"));
            assertThat(money(actual.occupiedAreaSqm()))
                    .as("type occupied %s", actual.type())
                    .isEqualTo(text(expectedType, "occupied_area_sqm"));
            assertThat(money(actual.occupancyPercent()))
                    .as("type percent %s", actual.type())
                    .isEqualTo(text(expectedType, "occupancy_percent"));
        }

        JsonNode expectedFloors = expectedRow.get("by_floor");
        assertThat(summary.byFloor().size()).as("by_floor size").isEqualTo(expectedFloors.size());
        for (int index = 0; index < expectedFloors.size(); index++) {
            JsonNode expectedFloor = expectedFloors.get(index);
            AnalyticsService.FloorOccupancy actual = summary.byFloor().get(index);
            assertThat(actual.floor())
                    .as("floor number %d", index)
                    .isEqualTo(expectedFloor.get("floor").asInt());
            assertThat(money(actual.totalAreaSqm()))
                    .as("floor total %d", actual.floor())
                    .isEqualTo(text(expectedFloor, "total_area_sqm"));
            assertThat(money(actual.occupiedAreaSqm()))
                    .as("floor occupied %d", actual.floor())
                    .isEqualTo(text(expectedFloor, "occupied_area_sqm"));
            assertThat(money(actual.occupancyPercent()))
                    .as("floor percent %d", actual.floor())
                    .isEqualTo(text(expectedFloor, "occupancy_percent"));
        }

        JsonNode expectedExpiring = expectedRow.get("expiring_contracts_30_days");
        assertThat(summary.expiringContracts().size())
                .as("expiring contracts size")
                .isEqualTo(expectedExpiring.size());
        for (int index = 0; index < expectedExpiring.size(); index++) {
            JsonNode expectedItem = expectedExpiring.get(index);
            AnalyticsService.ExpiringContract actual = summary.expiringContracts().get(index);
            assertThat(actual.contractId())
                    .isEqualTo(expectedItem.get("contract_id").asLong());
            assertThat(actual.endDate().toString()).isEqualTo(text(expectedItem, "end_date"));
            assertThat(actual.daysLeft()).isEqualTo(expectedItem.get("days_left").asLong());
        }
    }
}
