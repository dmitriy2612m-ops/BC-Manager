package ru.bcmanager.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;
import ru.bcmanager.domain.premises.ConferenceRoom;
import ru.bcmanager.domain.premises.OfficeSpace;
import ru.bcmanager.domain.premises.ParkingSpot;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.domain.premises.RetailSpace;
import ru.bcmanager.dto.DtoMapper;
import ru.bcmanager.dto.PremisesDtos;
import ru.bcmanager.service.ContractService;
import ru.bcmanager.service.PremisesService;
import ru.bcmanager.service.SpaceMatchingService;

/**
 * Реестр помещений и умный подбор (Plan.md, раздел 5.2).
 *
 * <p>Чтение доступно обеим ролям, изменение реестра — только {@code ADMIN};
 * ограничение продублировано правилами {@code SecurityConfig} и
 * {@link PreAuthorize} для явности. Подбор помещения использует Алгоритм 2.
 */
@RestController
@RequestMapping("/api/v1/premises")
@Tag(name = "Помещения", description = "Реестр коммерческих площадей и умный подбор")
public class PremisesController {

    private final PremisesService premisesService;

    private final ContractService contractService;

    private final SpaceMatchingService matchingService;

    private final DtoMapper mapper;

    /**
     * Создать контроллер помещений.
     *
     * @param premisesService CRUD-сервис помещений
     * @param contractService сервис договоров
     * @param matchingService сервис подбора
     * @param mapper          преобразователь в DTO
     */
    public PremisesController(
            PremisesService premisesService,
            ContractService contractService,
            SpaceMatchingService matchingService,
            DtoMapper mapper) {
        this.premisesService = premisesService;
        this.contractService = contractService;
        this.matchingService = matchingService;
        this.mapper = mapper;
    }

    /**
     * Список помещений с фильтрами.
     *
     * @param type     тип помещения
     * @param status   статус
     * @param floor    этаж
     * @param building корпус
     * @param minArea  минимальная площадь
     * @param maxArea  максимальная площадь
     * @return список карточек помещений
     */
    @GetMapping
    @Operation(summary = "Список помещений с фильтрами")
    public List<PremisesDtos.PremisesResponse> findAll(
            @RequestParam(required = false) PremisesType type,
            @RequestParam(required = false) PremisesStatus status,
            @RequestParam(required = false) Integer floor,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) BigDecimal minArea,
            @RequestParam(required = false) BigDecimal maxArea) {
        return mapper.toPremisesList(
                premisesService.findAll(type, status, floor, building, minArea, maxArea));
    }

    /**
     * Карточка помещения.
     *
     * @param id идентификатор
     * @return карточка
     */
    @GetMapping("/{id}")
    @Operation(summary = "Карточка помещения")
    public PremisesDtos.PremisesResponse findById(@PathVariable Long id) {
        return mapper.toPremises(premisesService.findById(id));
    }

    /**
     * Создать помещение (только {@code ADMIN}).
     *
     * @param request данные помещения
     * @return созданная карточка
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Создать помещение (ADMIN)")
    public ResponseEntity<PremisesDtos.PremisesResponse> create(
            @Valid @RequestBody PremisesDtos.PremisesRequest request) {
        Premises saved = premisesService.create(buildPremises(request, null));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toPremises(saved));
    }

    /**
     * Обновить помещение (только {@code ADMIN}).
     *
     * @param id      идентификатор
     * @param request новые значения
     * @return обновлённая карточка
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Обновить помещение (ADMIN)")
    public PremisesDtos.PremisesResponse update(
            @PathVariable Long id, @Valid @RequestBody PremisesDtos.PremisesRequest request) {
        Premises existing = premisesService.findById(id);
        Premises updated = premisesService.update(id, buildPremises(request, existing));
        return mapper.toPremises(updated);
    }

    /**
     * Удалить помещение без активных договоров (только {@code ADMIN}).
     *
     * @param id идентификатор
     * @return пустой ответ {@code 204}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Удалить помещение (ADMIN)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        premisesService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Умный подбор помещений (Алгоритм 2).
     *
     * @param request критерии подбора
     * @return ранжированные кандидаты
     */
    @PostMapping("/match")
    @Operation(summary = "Умный подбор и ранжирование помещений")
    public PremisesDtos.MatchResponse match(
            @Valid @RequestBody PremisesDtos.MatchRequest request) {
        List<Premises> premisesList = premisesService.findAllInWorkspace();
        List<ru.bcmanager.service.SpaceMatchingService.MatchCandidate> candidates =
                matchingService.matchAndRank(
                        premisesList,
                        contractService.findAllInWorkspaceWithDetails(),
                        new SpaceMatchingService.MatchRequest(
                                request.type(),
                                request.targetArea(),
                                request.minArea(),
                                request.maxArea(),
                                request.maxBudget(),
                                request.startDate(),
                                request.endDate()),
                        ru.bcmanager.service.LeasePricingService.DEFAULT_VAT_RATE);

        List<PremisesDtos.MatchCandidateResponse> rows = new ArrayList<>(candidates.size());
        for (ru.bcmanager.service.SpaceMatchingService.MatchCandidate candidate : candidates) {
            rows.add(mapper.toCandidate(candidate));
        }
        return new PremisesDtos.MatchResponse(request, rows);
    }

    /**
     * Построить полиморфный наследник {@code Premises} по типу запроса.
     *
     * <p>Тип помещения хранится в дискриминаторе, поэтому сменить тип
     * существующего помещения нельзя: для нового типа создаётся новый объект.
     *
     * @param request  данные запроса
     * @param existing существующее помещение при обновлении (может быть {@code null})
     * @return наследник помещения
     */
    private static Premises buildPremises(
            PremisesDtos.PremisesRequest request, Premises existing) {
        PremisesStatus status = request.status() == null
                ? PremisesStatus.AVAILABLE
                : request.status();

        Premises premises;
        if (existing != null && existing.getPremisesType() == request.premisesType()) {
            premises = existing;
            premises.setStatus(status);
        } else {
            premises = switch (request.premisesType()) {
                case OFFICE -> new OfficeSpace(
                        request.code(), request.building(), request.floor(),
                        request.areaSqm(), request.baseRatePerSqm(), status,
                        request.hasFurniture());
                case RETAIL -> new RetailSpace(
                        request.code(), request.building(), request.floor(),
                        request.areaSqm(), request.baseRatePerSqm(), status,
                        request.hasShowcase());
                case CONFERENCE -> new ConferenceRoom(
                        request.code(), request.building(), request.floor(),
                        request.areaSqm(), request.baseRatePerSqm(), status,
                        request.capacityPeople());
                case PARKING -> new ParkingSpot(
                        request.code(), request.building(), request.floor(),
                        request.areaSqm(), request.baseRatePerSqm(), status,
                        request.covered());
            };
        }

        premises.setCode(request.code());
        premises.setBuilding(request.building());
        premises.setFloor(request.floor());
        premises.setAreaSqm(request.areaSqm());
        premises.setBaseRatePerSqm(request.baseRatePerSqm());
        premises.setStatus(status);
        premises.setHasFurniture(request.hasFurniture());
        premises.setHasShowcase(request.hasShowcase());
        premises.setCapacityPeople(request.capacityPeople());
        premises.setCovered(request.covered());
        premises.setDescription(request.description());
        return premises;
    }
}
