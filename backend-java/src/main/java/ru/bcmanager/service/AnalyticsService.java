package ru.bcmanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.enums.ContractStatus;
import ru.bcmanager.domain.enums.MaintenancePriority;
import ru.bcmanager.domain.enums.PaymentStatus;
import ru.bcmanager.domain.premises.Premises;

/**
 * Аналитика загруженности и доходности (Plan.md, раздел 4.1, Алгоритм 6).
 *
 * <p>Группировка по типам и этажам выполняется через {@link HashMap}, а этажи
 * выводятся в возрастающем порядке через {@link TreeMap}, чтобы совпадать с
 * процедурным ядром Python. Занятым считается помещение, у которого есть
 * договор в статусе {@code ACTIVE}, покрывающий отчётную дату.
 *
 * <p>CSV-экспорт использует полиморфный метод {@code toCsvRow()} интерфейса
 * {@code Exportable}, реализованный иерархией помещений.
 */
@Service
public class AnalyticsService {

    /** Точность денежных величин. */
    public static final int MONEY_SCALE = 2;

    /** Точность процентов. */
    public static final int PERCENT_SCALE = 2;

    /** Округление денежных и процентных величин. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Окно (дней) для блока истекающих договоров. */
    public static final int EXPIRING_WINDOW_DAYS = 30;

    /** Стабильный порядок типов помещений (совпадает с порядком enum). */
    private static final List<String> TYPE_ORDER =
            List.of("OFFICE", "RETAIL", "CONFERENCE", "PARKING");

    /** Заголовок CSV-выгрузки договоров. */
    public static final String CONTRACTS_CSV_HEADER =
            "contract_number;premises_code;premises_type;floor;tenant;inn;start_date;end_date;monthly_rent;status";

    /** Заголовок CSV-выгрузки помещений. */
    public static final String PREMISES_CSV_HEADER =
            "code;building;floor;type;area_sqm;base_rate;status;description";

    /**
     * Рассчитать KPI: загруженность, MRR, доходность и истекающие договоры.
     *
     * @param premisesList       все помещения
     * @param contracts          все договоры
     * @param payments           все платежи (для индикатора просрочки)
     * @param maintenanceRequests все заявки на ТО
     * @param asOf               отчётная дата
     * @return сводка аналитики
     */
    public AnalyticsSummary compute(
            List<Premises> premisesList,
            List<LeaseContract> contracts,
            List<Payment> payments,
            List<MaintenanceRequest> maintenanceRequests,
            LocalDate asOf) {
        Map<Long, Boolean> occupied = new HashMap<>();
        BigDecimal mrr = BigDecimal.ZERO;
        for (LeaseContract contract : contracts) {
            if (contract.getStatus() != ContractStatus.ACTIVE) {
                continue;
            }
            if (contract.coversDate(asOf)) {
                occupied.put(contract.getPremises().getId(), Boolean.TRUE);
                mrr = mrr.add(contract.getMonthlyRent());
            }
        }

        BigDecimal totalArea = BigDecimal.ZERO;
        BigDecimal occupiedArea = BigDecimal.ZERO;
        Map<String, AreaBucket> byType = new HashMap<>();
        Map<Integer, AreaBucket> byFloor = new TreeMap<>();

        for (Premises premises : premisesList) {
            BigDecimal area = premises.getAreaSqm();
            boolean isOccupied = Boolean.TRUE.equals(occupied.get(premises.getId()));

            totalArea = totalArea.add(area);
            if (isOccupied) {
                occupiedArea = occupiedArea.add(area);
            }

            byType.computeIfAbsent(
                            premises.getPremisesType().name(), key -> new AreaBucket())
                    .add(area, isOccupied);
            byFloor.computeIfAbsent(premises.getFloor(), key -> new AreaBucket())
                    .add(area, isOccupied);
        }

        totalArea = money(totalArea);
        occupiedArea = money(occupiedArea);
        mrr = money(mrr);

        BigDecimal averageYield = occupiedArea.signum() > 0
                ? money(mrr.divide(occupiedArea, 10, ROUNDING))
                : BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);

        List<TypeOccupancy> typeRows = new ArrayList<>(byType.size());
        for (String typeName : TYPE_ORDER) {
            AreaBucket bucket = byType.get(typeName);
            if (bucket == null) {
                continue;
            }
            typeRows.add(new TypeOccupancy(
                    typeName,
                    money(bucket.total()),
                    money(bucket.occupied()),
                    percent(bucket.occupied(), bucket.total())));
        }

        List<FloorOccupancy> floorRows = new ArrayList<>(byFloor.size());
        for (Map.Entry<Integer, AreaBucket> entry : byFloor.entrySet()) {
            AreaBucket bucket = entry.getValue();
            floorRows.add(new FloorOccupancy(
                    entry.getKey(),
                    money(bucket.total()),
                    money(bucket.occupied()),
                    percent(bucket.occupied(), bucket.total())));
        }

        LocalDate horizon = asOf.plusDays(EXPIRING_WINDOW_DAYS);
        List<ExpiringContract> expiring = new ArrayList<>();
        for (LeaseContract contract : contracts) {
            if (contract.getStatus() != ContractStatus.ACTIVE) {
                continue;
            }
            LocalDate endDate = contract.getEndDate();
            if (!endDate.isBefore(asOf) && !endDate.isAfter(horizon)) {
                expiring.add(new ExpiringContract(
                        contract.getId(),
                        contract.getContractNumber(),
                        endDate,
                        ChronoUnit.DAYS.between(asOf, endDate)));
            }
        }
        expiring.sort((left, right) -> {
            int byDate = left.endDate().compareTo(right.endDate());
            if (byDate != 0) {
                return byDate;
            }
            return Long.compare(left.contractId(), right.contractId());
        });

        long openCritical = maintenanceRequests.stream()
                .filter(request -> request.getPriority() == MaintenancePriority.CRITICAL)
                .filter(request -> request.getStatus().isActive())
                .count();

        BigDecimal overdueAmount = BigDecimal.ZERO;
        long overdueCount = 0;
        for (Payment payment : payments) {
            if (payment.getStatus() == PaymentStatus.OVERDUE) {
                overdueAmount = overdueAmount.add(
                        payment.getAmountDue().subtract(payment.getPaidAmount()));
                overdueCount += 1;
            }
        }

        return new AnalyticsSummary(
                asOf,
                totalArea,
                occupiedArea,
                percent(occupiedArea, totalArea),
                mrr,
                averageYield,
                List.copyOf(typeRows),
                List.copyOf(floorRows),
                List.copyOf(expiring),
                (int) openCritical,
                (int) overdueCount,
                money(overdueAmount));
    }

    /**
     * Сформировать CSV-выгрузку договоров с данными помещений и арендаторов.
     *
     * @param contracts     все договоры
     * @param premisesList  все помещения
     * @param withHeader    добавлять ли строку заголовка
     * @return текст CSV с разделителем {@code ';'}
     */
    public String contractsCsv(
            List<LeaseContract> contracts, List<Premises> premisesList, boolean withHeader) {
        Map<Long, Premises> premisesById = new HashMap<>();
        for (Premises premises : premisesList) {
            premisesById.put(premises.getId(), premises);
        }

        List<LeaseContract> sorted = new ArrayList<>(contracts);
        sorted.sort((left, right) ->
                left.getContractNumber().compareTo(right.getContractNumber()));

        StringBuilder builder = new StringBuilder();
        if (withHeader) {
            builder.append(CONTRACTS_CSV_HEADER).append('\n');
        }
        for (LeaseContract contract : sorted) {
            Premises premises = premisesById.get(contract.getPremises().getId());
            String premisesCode = premises == null ? "" : premises.getCode();
            String premisesType = premises == null ? "" : premises.resolvePremisesType().name();
            String floor = premises == null ? "" : String.valueOf(premises.getFloor());
            String tenantName = contract.getTenant() == null ? "" : contract.getTenant().getCompanyName();
            String inn = contract.getTenant() == null ? "" : contract.getTenant().getInn();

            builder.append(String.join(
                            ";",
                            escape(contract.getContractNumber()),
                            escape(premisesCode),
                            escape(premisesType),
                            escape(floor),
                            escape(tenantName),
                            escape(inn),
                            contract.getStartDate().toString(),
                            contract.getEndDate().toString(),
                            contract.getMonthlyRent().toPlainString(),
                            contract.getStatus().name()))
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * Сформировать CSV-выгрузку реестра помещений через {@code Exportable}.
     *
     * @param premisesList все помещения
     * @param withHeader   добавлять ли строку заголовка
     * @return текст CSV с разделителем {@code ';'}
     */
    public String premisesCsv(List<Premises> premisesList, boolean withHeader) {
        List<Premises> sorted = new ArrayList<>(premisesList);
        sorted.sort((left, right) -> left.getCode().compareTo(right.getCode()));

        StringBuilder builder = new StringBuilder();
        if (withHeader) {
            builder.append(PREMISES_CSV_HEADER).append('\n');
        }
        for (Premises premises : sorted) {
            builder.append(premises.toCsvRow()).append('\n');
        }
        return builder.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(';') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) {
            return BigDecimal.ZERO.setScale(PERCENT_SCALE, ROUNDING);
        }
        return part.divide(total, 10, ROUNDING)
                .multiply(BigDecimal.valueOf(100))
                .setScale(PERCENT_SCALE, ROUNDING);
    }

    /** Изменяемый аккумулятор площади по группе. */
    private static final class AreaBucket {

        private BigDecimal total = BigDecimal.ZERO;
        private BigDecimal occupied = BigDecimal.ZERO;

        void add(BigDecimal area, boolean isOccupied) {
            total = total.add(area);
            if (isOccupied) {
                occupied = occupied.add(area);
            }
        }

        BigDecimal total() {
            return total;
        }

        BigDecimal occupied() {
            return occupied;
        }
    }

    /**
     * Сводка KPI.
     *
     * @param asOf                 отчётная дата
     * @param totalAreaSqm         общая площадь
     * @param occupiedAreaSqm      занятая площадь
     * @param occupancyPercent     загруженность, %
     * @param mrr                  месячная выручка по активным договорам
     * @param averageYieldPerSqm   средняя доходность на 1 м² занятой площади
     * @param byType               загруженность по типам
     * @param byFloor              загруженность по этажам
     * @param expiringContracts    договоры, истекающие в ближайшие 30 дней
     * @param openCriticalRequests количество открытых CRITICAL-заявок
     * @param overduePaymentsCount количество просроченных платежей
     * @param overdueAmount        сумма просроченной задолженности
     */
    public record AnalyticsSummary(
            LocalDate asOf,
            BigDecimal totalAreaSqm,
            BigDecimal occupiedAreaSqm,
            BigDecimal occupancyPercent,
            BigDecimal mrr,
            BigDecimal averageYieldPerSqm,
            List<TypeOccupancy> byType,
            List<FloorOccupancy> byFloor,
            List<ExpiringContract> expiringContracts,
            int openCriticalRequests,
            int overduePaymentsCount,
            BigDecimal overdueAmount) {
    }

    /**
     * Загруженность по типу помещения.
     *
     * @param type              тип помещения
     * @param totalAreaSqm      общая площадь
     * @param occupiedAreaSqm   занятая площадь
     * @param occupancyPercent  загруженность, %
     */
    public record TypeOccupancy(
            String type,
            BigDecimal totalAreaSqm,
            BigDecimal occupiedAreaSqm,
            BigDecimal occupancyPercent) {
    }

    /**
     * Загруженность по этажу.
     *
     * @param floor             номер этажа
     * @param totalAreaSqm      общая площадь
     * @param occupiedAreaSqm   занятая площадь
     * @param occupancyPercent  загруженность, %
     */
    public record FloorOccupancy(
            int floor,
            BigDecimal totalAreaSqm,
            BigDecimal occupiedAreaSqm,
            BigDecimal occupancyPercent) {
    }

    /**
     * Договор, истекающий в окне аналитики.
     *
     * @param contractId     идентификатор договора
     * @param contractNumber номер договора
     * @param endDate        дата окончания
     * @param daysLeft       дней до окончания
     */
    public record ExpiringContract(
            Long contractId, String contractNumber, LocalDate endDate, long daysLeft) {
    }
}
