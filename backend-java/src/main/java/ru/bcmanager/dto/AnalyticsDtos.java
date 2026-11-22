package ru.bcmanager.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTO аналитики и экспорта (Plan.md, раздел 5.7).
 */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    /**
     * Загруженность по типу помещения.
     *
     * @param type             тип
     * @param totalAreaSqm     общая площадь
     * @param occupiedAreaSqm  занятая площадь
     * @param occupancyPercent загруженность, %
     */
    public record TypeOccupancyRow(
            String type,
            BigDecimal totalAreaSqm,
            BigDecimal occupiedAreaSqm,
            BigDecimal occupancyPercent) {
    }

    /**
     * Загруженность по этажу.
     *
     * @param floor            этаж
     * @param totalAreaSqm     общая площадь
     * @param occupiedAreaSqm  занятая площадь
     * @param occupancyPercent загруженность, %
     */
    public record FloorOccupancyRow(
            int floor,
            BigDecimal totalAreaSqm,
            BigDecimal occupiedAreaSqm,
            BigDecimal occupancyPercent) {
    }

    /**
     * Договор, истекающий в окне аналитики.
     *
     * @param contractId     идентификатор
     * @param contractNumber номер
     * @param endDate        дата окончания
     * @param daysLeft       дней до окончания
     */
    public record ExpiringContractRow(
            Long contractId, String contractNumber, LocalDate endDate, long daysLeft) {
    }

    /**
     * Сводка KPI для дашборда.
     *
     * @param asOf                 отчётная дата
     * @param totalAreaSqm         общая площадь
     * @param occupiedAreaSqm      занятая площадь
     * @param occupancyPercent     загруженность, %
     * @param mrr                  месячная выручка по активным договорам
     * @param averageYieldPerSqm   доходность на 1 м² занятой площади
     * @param byType               загруженность по типам
     * @param byFloor              загруженность по этажам
     * @param expiringContracts    истекающие договоры (30 дней)
     * @param openCriticalRequests открытые CRITICAL-заявки
     * @param overduePaymentsCount просроченные платежи
     * @param overdueAmount        сумма просроченной задолженности
     */
    public record AnalyticsSummaryResponse(
            LocalDate asOf,
            BigDecimal totalAreaSqm,
            BigDecimal occupiedAreaSqm,
            BigDecimal occupancyPercent,
            BigDecimal mrr,
            BigDecimal averageYieldPerSqm,
            List<TypeOccupancyRow> byType,
            List<FloorOccupancyRow> byFloor,
            List<ExpiringContractRow> expiringContracts,
            int openCriticalRequests,
            int overduePaymentsCount,
            BigDecimal overdueAmount) {
    }
}
