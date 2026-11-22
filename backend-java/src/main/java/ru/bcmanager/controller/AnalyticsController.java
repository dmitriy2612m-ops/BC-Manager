package ru.bcmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.dto.AnalyticsDtos;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.repository.MaintenanceRequestRepository;
import ru.bcmanager.repository.PaymentRepository;
import ru.bcmanager.repository.PremisesRepository;
import ru.bcmanager.service.AnalyticsService;

/**
 * Аналитика и экспорт (Plan.md, раздел 5.7).
 *
 * <p>Сводка KPI строится Алгоритмом 6 через {@link AnalyticsService}; CSV
 * отдаётся с BOM в кодировке UTF-8, чтобы Excel корректно открывал кириллицу.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Аналитика", description = "Сводка KPI и экспорт в CSV")
public class AnalyticsController {

    /** Маркер BOM для корректного открытия UTF-8 CSV в Excel. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final AnalyticsService analyticsService;

    private final PremisesRepository premisesRepository;

    private final LeaseContractRepository contractRepository;

    private final PaymentRepository paymentRepository;

    private final MaintenanceRequestRepository maintenanceRepository;

    /**
     * Создать контроллер аналитики.
     *
     * @param analyticsService     сервис аналитики
     * @param premisesRepository   репозиторий помещений
     * @param contractRepository   репозиторий договоров
     * @param paymentRepository    репозиторий платежей
     * @param maintenanceRepository репозиторий заявок
     */
    public AnalyticsController(
            AnalyticsService analyticsService,
            PremisesRepository premisesRepository,
            LeaseContractRepository contractRepository,
            PaymentRepository paymentRepository,
            MaintenanceRequestRepository maintenanceRepository) {
        this.analyticsService = analyticsService;
        this.premisesRepository = premisesRepository;
        this.contractRepository = contractRepository;
        this.paymentRepository = paymentRepository;
        this.maintenanceRepository = maintenanceRepository;
    }

    /**
     * Сводка KPI для дашборда (Алгоритм 6).
     *
     * @param asOf отчётная дата (по умолчанию — сегодня)
     * @return сводка загруженности, MRR, задолженности и истекающих договоров
     */
    @GetMapping("/summary")
    @Operation(summary = "Сводка KPI (загруженность, MRR, долг, истекающие договоры)")
    public AnalyticsDtos.AnalyticsSummaryResponse summary(
            @RequestParam(required = false) LocalDate asOf) {
        LocalDate reportDate = asOf == null ? LocalDate.now() : asOf;

        List<Premises> premisesList = premisesRepository.findAll();
        List<LeaseContract> contracts = contractRepository.findAllWithDetails();
        List<Payment> payments = paymentRepository.findAll();
        List<MaintenanceRequest> requests = maintenanceRepository.findAll();

        AnalyticsService.AnalyticsSummary summary = analyticsService.compute(
                premisesList, contracts, payments, requests, reportDate);

        List<AnalyticsDtos.TypeOccupancyRow> byType = new ArrayList<>();
        for (AnalyticsService.TypeOccupancy row : summary.byType()) {
            byType.add(new AnalyticsDtos.TypeOccupancyRow(
                    row.type(),
                    row.totalAreaSqm(),
                    row.occupiedAreaSqm(),
                    row.occupancyPercent()));
        }

        List<AnalyticsDtos.FloorOccupancyRow> byFloor = new ArrayList<>();
        for (AnalyticsService.FloorOccupancy row : summary.byFloor()) {
            byFloor.add(new AnalyticsDtos.FloorOccupancyRow(
                    row.floor(),
                    row.totalAreaSqm(),
                    row.occupiedAreaSqm(),
                    row.occupancyPercent()));
        }

        List<AnalyticsDtos.ExpiringContractRow> expiring = new ArrayList<>();
        for (AnalyticsService.ExpiringContract row : summary.expiringContracts()) {
            expiring.add(new AnalyticsDtos.ExpiringContractRow(
                    row.contractId(), row.contractNumber(), row.endDate(), row.daysLeft()));
        }

        return new AnalyticsDtos.AnalyticsSummaryResponse(
                summary.asOf(),
                summary.totalAreaSqm(),
                summary.occupiedAreaSqm(),
                summary.occupancyPercent(),
                summary.mrr(),
                summary.averageYieldPerSqm(),
                byType,
                byFloor,
                expiring,
                summary.openCriticalRequests(),
                summary.overduePaymentsCount(),
                summary.overdueAmount());
    }

    /**
     * CSV-выгрузка реестра договоров с помещениями и арендаторами.
     *
     * @return файл {@code contracts.csv} в UTF-8 с BOM
     */
    @GetMapping("/export/csv")
    @Operation(summary = "Экспорт реестра договоров в CSV (UTF-8 с BOM)")
    public ResponseEntity<byte[]> exportContractsCsv() {
        String csv = analyticsService.contractsCsv(
                contractRepository.findAllWithDetails(),
                premisesRepository.findAll(),
                true);
        return csvResponse("contracts.csv", csv);
    }

    /**
     * CSV-выгрузка реестра помещений.
     *
     * @return файл {@code premises.csv} в UTF-8 с BOM
     */
    @GetMapping("/export/premises/csv")
    @Operation(summary = "Экспорт реестра помещений в CSV (UTF-8 с BOM)")
    public ResponseEntity<byte[]> exportPremisesCsv() {
        String csv = analyticsService.premisesCsv(premisesRepository.findAll(), true);
        return csvResponse("premises.csv", csv);
    }

    private static ResponseEntity<byte[]> csvResponse(String fileName, String csv) {
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, payload, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, payload, UTF8_BOM.length, body.length);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(payload);
    }
}
