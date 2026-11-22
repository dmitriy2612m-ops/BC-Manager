package ru.bcmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.bcmanager.dto.MaintenanceDtos;
import ru.bcmanager.service.MaintenanceService;
import ru.bcmanager.service.RegistryReadService;

/**
 * Заявки на техобслуживание (Plan.md, раздел 5.6).
 *
 * <p>С параметром {@code prioritized=true} возвращается очередь, упорядоченная
 * по Алгоритму 5 с учётом SLA-эскалации; иначе — заявки в порядке поступления.
 */
@RestController
@RequestMapping("/api/v1/maintenance")
@Tag(name = "Заявки на ТО", description = "Приоритетная очередь и обработка заявок")
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    private final RegistryReadService registryReadService;

    /**
     * Создать контроллер заявок.
     *
     * @param maintenanceService  сервис заявок
     * @param registryReadService чтение реестров внутри транзакции
     */
    public MaintenanceController(
            MaintenanceService maintenanceService,
            RegistryReadService registryReadService) {
        this.maintenanceService = maintenanceService;
        this.registryReadService = registryReadService;
    }

    /**
     * Список заявок; с {@code prioritized=true} — в порядке приоритетной очереди.
     *
     * @param prioritized сортировать ли по Алгоритму 5
     * @param now         контрольный момент времени (по умолчанию — текущий)
     * @return заявки
     */
    @GetMapping
    @Operation(summary = "Список заявок (prioritized=true — очередь по Алгоритму 5)")
    public List<MaintenanceDtos.MaintenanceResponse> findAll(
            @RequestParam(defaultValue = "false") boolean prioritized,
            @RequestParam(required = false) LocalDateTime now) {
        LocalDateTime controlPoint = now == null ? LocalDateTime.now() : now;
        return registryReadService.findMaintenance(prioritized, controlPoint);
    }

    /**
     * Создать заявку.
     *
     * @param request данные заявки
     * @return созданная заявка
     */
    @PostMapping
    @Operation(summary = "Создать заявку на ТО")
    public ResponseEntity<MaintenanceDtos.MaintenanceResponse> create(
            @Valid @RequestBody MaintenanceDtos.CreateMaintenanceRequest request) {
        MaintenanceDtos.MaintenanceResponse saved = maintenanceService.createResponse(
                request.premisesId(),
                request.tenantId(),
                request.title(),
                request.description(),
                request.category(),
                request.priority());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Изменить статус заявки.
     *
     * @param id      идентификатор
     * @param request новый статус
     * @return обновлённая заявка
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "Изменить статус заявки (IN_PROGRESS, RESOLVED, CANCELLED)")
    public MaintenanceDtos.MaintenanceResponse changeStatus(
            @PathVariable Long id,
            @Valid @RequestBody MaintenanceDtos.ChangeStatusRequest request) {
        return maintenanceService.changeStatusResponse(id, request.status());
    }
}
