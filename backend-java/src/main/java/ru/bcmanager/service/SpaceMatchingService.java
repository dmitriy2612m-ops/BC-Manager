package ru.bcmanager.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.enums.ContractStatus;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;
import ru.bcmanager.domain.premises.Premises;

/**
 * Проверка пересечений аренды и умный подбор помещений (Plan.md, раздел 4.1,
 * Алгоритмы 1 и 2).
 *
 * <p>Сервис не обращается к БД напрямую: он принимает уже загруженные списки
 * помещений и договоров, что позволяет использовать один и тот же код и в
 * REST-слое, и в parity-тесте. Для ускорения проверки конфликтов договоры
 * группируются по помещению через {@link HashMap}.
 */
@Service
public class SpaceMatchingService {

    /** Статусы договоров, участвующие в проверке конфликтов. */
    public static final List<ContractStatus> CONFLICT_STATUSES =
            List.of(ContractStatus.ACTIVE, ContractStatus.DRAFT);

    /** Точность индекса релевантности. */
    private static final int SCORE_SCALE = 2;

    /** Максимальный вклад штрафа (площадь и цена по 50 баллов). */
    private static final BigDecimal FIFTY = new BigDecimal("50");

    /** База индекса релевантности. */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final LeasePricingService pricingService;

    /**
     * Создать сервис подбора.
     *
     * @param pricingService сервис расчёта стоимости (для оценки бюджета)
     */
    public SpaceMatchingService(LeasePricingService pricingService) {
        this.pricingService = pricingService;
    }

    /**
     * Проверить пересечение запрашиваемого интервала с действующими договорами.
     *
     * @param contracts          все договоры помещения (или весь реестр)
     * @param premisesId         идентификатор помещения
     * @param startDate          начало запрашиваемого периода (включительно)
     * @param endDate            конец запрашиваемого периода (включительно)
     * @param excludeContractId  договор, который нужно игнорировать (при правке)
     * @return результат проверки
     */
    public ConflictResult checkLeaseConflict(
            List<LeaseContract> contracts,
            Long premisesId,
            LocalDate startDate,
            LocalDate endDate,
            Long excludeContractId) {
        LeaseContract best = null;
        for (LeaseContract contract : contracts) {
            if (!premisesId.equals(premisesIdOf(contract))) {
                continue;
            }
            if (!CONFLICT_STATUSES.contains(contract.getStatus())) {
                continue;
            }
            if (excludeContractId != null && excludeContractId.equals(contract.getId())) {
                continue;
            }
            if (!contract.overlaps(startDate, endDate)) {
                continue;
            }
            // Детерминированно сообщаем о самом раннем конфликтующем договоре.
            if (best == null || contract.getStartDate().isBefore(best.getStartDate())) {
                best = contract;
            }
        }
        if (best == null) {
            return ConflictResult.none();
        }
        return new ConflictResult(true, best.getId(), best.getContractNumber());
    }

    /**
     * Отфильтровать и отранжировать помещения под заявку арендатора.
     *
     * @param premisesList все помещения
     * @param contracts    все договоры
     * @param request      критерии подбора
     * @param vatRate      ставка НДС для оценки бюджета
     * @return кандидаты по убыванию релевантности
     */
    public List<MatchCandidate> matchAndRank(
            List<Premises> premisesList,
            List<LeaseContract> contracts,
            MatchRequest request,
            BigDecimal vatRate) {
        BigDecimal targetArea = request.targetArea() == null ? BigDecimal.ZERO : request.targetArea();
        BigDecimal minArea = request.minArea() == null ? BigDecimal.ZERO : request.minArea();
        BigDecimal maxArea = request.maxArea() == null ? new BigDecimal("1e12") : request.maxArea();
        BigDecimal maxBudget = request.maxBudget() == null ? new BigDecimal("1e12") : request.maxBudget();
        BigDecimal areaFloor = targetArea.max(BigDecimal.ONE);

        Map<Long, List<LeaseContract>> byPremises = groupByPremises(contracts);
        List<MatchCandidate> candidates = new ArrayList<>();

        for (Premises premises : premisesList) {
            if (premises.getStatus() != PremisesStatus.AVAILABLE) {
                continue;
            }
            if (request.type() != null && premises.getPremisesType() != request.type()) {
                continue;
            }
            BigDecimal area = premises.getAreaSqm();
            if (area.compareTo(minArea) < 0 || area.compareTo(maxArea) > 0) {
                continue;
            }

            ConflictResult conflict = checkLeaseConflict(
                    byPremises.getOrDefault(premises.getId(), List.of()),
                    premises.getId(),
                    request.startDate(),
                    request.endDate(),
                    null);
            if (conflict.hasConflict()) {
                continue;
            }

            BigDecimal cost = pricingService
                    .calculate(premises, request.startDate(), request.endDate(), vatRate)
                    .totalMonthly();
            if (cost.compareTo(maxBudget) > 0) {
                continue;
            }

            BigDecimal areaPenalty = FIFTY
                    .multiply(area.subtract(targetArea).abs())
                    .divide(areaFloor, 10, RoundingMode.HALF_UP);
            BigDecimal pricePenalty = FIFTY.multiply(cost).divide(maxBudget, 10, RoundingMode.HALF_UP);
            BigDecimal score = HUNDRED.subtract(areaPenalty).subtract(pricePenalty);
            score = score.max(BigDecimal.ZERO).min(HUNDRED);
            score = score.setScale(SCORE_SCALE, RoundingMode.HALF_UP);

            candidates.add(new MatchCandidate(
                    premises.getId(),
                    premises.getCode(),
                    area,
                    cost,
                    score));
        }

        candidates.sort(Comparator
                .comparing(MatchCandidate::score, Comparator.reverseOrder())
                .thenComparing(MatchCandidate::estimatedMonthlyCost)
                .thenComparing(MatchCandidate::premisesId));

        List<MatchCandidate> ranked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            MatchCandidate candidate = candidates.get(index);
            ranked.add(new MatchCandidate(
                    candidate.premisesId(),
                    candidate.code(),
                    candidate.areaSqm(),
                    candidate.estimatedMonthlyCost(),
                    candidate.score(),
                    index + 1));
        }
        return ranked;
    }

    /**
     * Сгруппировать договоры по идентификатору помещения.
     *
     * @param contracts все договоры
     * @return карта «помещение → договоры»
     */
    public Map<Long, List<LeaseContract>> groupByPremises(List<LeaseContract> contracts) {
        Map<Long, List<LeaseContract>> grouped = new HashMap<>();
        for (LeaseContract contract : contracts) {
            Long premisesId = premisesIdOf(contract);
            if (premisesId != null) {
                grouped.computeIfAbsent(premisesId, key -> new ArrayList<>()).add(contract);
            }
        }
        return grouped;
    }

    private static Long premisesIdOf(LeaseContract contract) {
        return contract.getPremises() == null ? null : contract.getPremises().getId();
    }

    /**
     * Результат проверки конфликта (аналог dict Алгоритма 1).
     *
     * @param hasConflict               найден ли конфликт
     * @param conflictingContractId     идентификатор конфликтующего договора
     * @param conflictingContractNumber номер конфликтующего договора
     */
    public record ConflictResult(
            boolean hasConflict,
            Long conflictingContractId,
            String conflictingContractNumber) {

        static ConflictResult none() {
            return new ConflictResult(false, null, null);
        }
    }

    /**
     * Критерии подбора помещения.
     *
     * @param type       желаемый тип (может быть {@code null})
     * @param targetArea целевая площадь, м²
     * @param minArea    минимальная площадь, м²
     * @param maxArea    максимальная площадь, м²
     * @param maxBudget  максимальная месячная стоимость с НДС
     * @param startDate  начало желаемого периода
     * @param endDate    окончание желаемого периода
     */
    public record MatchRequest(
            PremisesType type,
            BigDecimal targetArea,
            BigDecimal minArea,
            BigDecimal maxArea,
            BigDecimal maxBudget,
            LocalDate startDate,
            LocalDate endDate) {
    }

    /**
     * Кандидат в ранжированном списке.
     *
     * @param premisesId           идентификатор помещения
     * @param code                 код помещения
     * @param areaSqm              площадь, м²
     * @param estimatedMonthlyCost оценка месячной стоимости с НДС
     * @param score                индекс релевантности 0..100
     * @param rank                 позиция в выдаче с 1
     */
    public record MatchCandidate(
            Long premisesId,
            String code,
            BigDecimal areaSqm,
            BigDecimal estimatedMonthlyCost,
            BigDecimal score,
            int rank) {

        MatchCandidate(
                Long premisesId,
                String code,
                BigDecimal areaSqm,
                BigDecimal estimatedMonthlyCost,
                BigDecimal score) {
            this(premisesId, code, areaSqm, estimatedMonthlyCost, score, 0);
        }
    }
}
