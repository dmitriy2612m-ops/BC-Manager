package ru.bcmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.dto.ContractDtos;
import ru.bcmanager.dto.DtoMapper;
import ru.bcmanager.dto.TenantDtos;
import ru.bcmanager.repository.LeaseContractRepository;
import ru.bcmanager.service.TenantService;

/**
 * Реестр арендаторов (Plan.md, раздел 5.3).
 *
 * <p>Поиск по названию или ИНН, карточка с договорами контрагента и мягкое
 * удаление через деактивацию.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@Tag(name = "Арендаторы", description = "Реестр контрагентов и их договоры")
public class TenantController {

    private final TenantService tenantService;

    private final LeaseContractRepository contractRepository;

    private final DtoMapper mapper;

    /**
     * Создать контроллер арендаторов.
     *
     * @param tenantService      сервис арендаторов
     * @param contractRepository репозиторий договоров
     * @param mapper             преобразователь в DTO
     */
    public TenantController(
            TenantService tenantService,
            LeaseContractRepository contractRepository,
            DtoMapper mapper) {
        this.tenantService = tenantService;
        this.contractRepository = contractRepository;
        this.mapper = mapper;
    }

    /**
     * Список арендаторов или поиск.
     *
     * @param query подстрока названия или ИНН
     * @return список арендаторов
     */
    @GetMapping
    @Operation(summary = "Список арендаторов (поиск по названию или ИНН)")
    public List<TenantDtos.TenantResponse> findAll(
            @RequestParam(required = false) String query) {
        List<TenantDtos.TenantResponse> result = new ArrayList<>();
        for (Tenant tenant : tenantService.findAll(query)) {
            result.add(mapper.toTenant(tenant));
        }
        return result;
    }

    /**
     * Карточка арендатора с договорами.
     *
     * @param id идентификатор
     * @return данные арендатора и его договоры
     */
    @GetMapping("/{id}")
    @Operation(summary = "Карточка арендатора и его договоры")
    public TenantDtos.TenantDetailsResponse findById(@PathVariable Long id) {
        Tenant tenant = tenantService.findById(id);
        List<LeaseContract> contracts =
                contractRepository.findByTenantId(tenant.getId());
        contracts.sort((left, right) ->
                right.getStartDate().compareTo(left.getStartDate()));

        List<ContractDtos.ContractSummary> summaries = new ArrayList<>(contracts.size());
        for (LeaseContract contract : contracts) {
            summaries.add(mapper.toContractSummary(contract));
        }
        return new TenantDtos.TenantDetailsResponse(mapper.toTenant(tenant), summaries);
    }

    /**
     * Зарегистрировать арендатора.
     *
     * @param request данные арендатора
     * @return созданная карточка
     */
    @PostMapping
    @Operation(summary = "Зарегистрировать арендатора")
    public ResponseEntity<TenantDtos.TenantResponse> create(
            @Valid @RequestBody TenantDtos.TenantRequest request) {
        Tenant saved = tenantService.create(new Tenant(
                request.companyName(),
                request.inn(),
                request.contactPerson(),
                request.phone(),
                request.email()));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toTenant(saved));
    }

    /**
     * Обновить арендатора.
     *
     * @param id      идентификатор
     * @param request новые значения
     * @return обновлённая карточка
     */
    @PutMapping("/{id}")
    @Operation(summary = "Обновить арендатора")
    public TenantDtos.TenantResponse update(
            @PathVariable Long id, @Valid @RequestBody TenantDtos.TenantRequest request) {
        Tenant patch = new Tenant(
                request.companyName(),
                request.inn(),
                request.contactPerson(),
                request.phone(),
                request.email());
        return mapper.toTenant(tenantService.update(id, patch));
    }

    /**
     * Деактивировать контрагента.
     *
     * @param id идентификатор
     * @return деактивированная карточка
     */
    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Деактивировать арендатора")
    public TenantDtos.TenantResponse deactivate(@PathVariable Long id) {
        return mapper.toTenant(tenantService.deactivate(id));
    }
}
