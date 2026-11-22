package ru.bcmanager.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import ru.bcmanager.domain.enums.PremisesStatus;
import ru.bcmanager.domain.enums.PremisesType;

/**
 * DTO реестра помещений и умного подбора (Plan.md, разделы 5.2).
 */
public final class PremisesDtos {

    private PremisesDtos() {
    }

    /**
     * Запрос создания/обновления помещения.
     *
     * @param code           код помещения
     * @param building       корпус
     * @param floor          этаж (может быть отрицательным для паркинга)
     * @param premisesType   тип помещения
     * @param areaSqm        площадь, м²
     * @param baseRatePerSqm базовая ставка за м²
     * @param status         статус доступности
     * @param hasFurniture   наличие мебели
     * @param hasShowcase    наличие витрины
     * @param capacityPeople вместимость (для переговорных)
     * @param covered        крытый паркинг
     * @param description    описание
     */
    public record PremisesRequest(
            @NotBlank(message = "Код помещения обязателен")
            @Size(max = 30, message = "Код не длиннее 30 символов")
            String code,

            @NotBlank(message = "Корпус обязателен")
            @Size(max = 50, message = "Корпус не длиннее 50 символов")
            String building,

            @NotNull(message = "Этаж обязателен")
            Integer floor,

            @NotNull(message = "Тип помещения обязателен")
            PremisesType premisesType,

            @NotNull(message = "Площадь обязательна")
            @Positive(message = "Площадь должна быть больше нуля")
            BigDecimal areaSqm,

            @NotNull(message = "Базовая ставка обязательна")
            @Positive(message = "Ставка должна быть больше нуля")
            BigDecimal baseRatePerSqm,

            PremisesStatus status,

            boolean hasFurniture,

            boolean hasShowcase,

            @Min(value = 0, message = "Вместимость не может быть отрицательной")
            Integer capacityPeople,

            boolean covered,

            @Size(max = 2000, message = "Описание не длиннее 2000 символов")
            String description) {
    }

    /**
     * Ответ с карточкой помещения.
     *
     * @param id                       идентификатор
     * @param code                     код
     * @param building                 корпус
     * @param floor                    этаж
     * @param premisesType             тип
     * @param areaSqm                  площадь, м²
     * @param baseRatePerSqm           базовая ставка за м²
     * @param status                   статус
     * @param hasFurniture             наличие мебели
     * @param hasShowcase              наличие витрины
     * @param capacityPeople           вместимость
     * @param covered                  крытый паркинг
     * @param description              описание
     * @param baseMonthlyCost          месячная ставка без OpEx и НДС
     * @param opexCost                 месячный OpEx
     */
    public record PremisesResponse(
            Long id,
            String code,
            String building,
            Integer floor,
            PremisesType premisesType,
            BigDecimal areaSqm,
            BigDecimal baseRatePerSqm,
            PremisesStatus status,
            boolean hasFurniture,
            boolean hasShowcase,
            Integer capacityPeople,
            boolean covered,
            String description,
            BigDecimal baseMonthlyCost,
            BigDecimal opexCost) {
    }

    /**
     * Критерии умного подбора (Алгоритм 2).
     *
     * @param type       желаемый тип помещения
     * @param targetArea целевая площадь, м²
     * @param minArea    минимальная площадь, м²
     * @param maxArea    максимальная площадь, м²
     * @param maxBudget  максимальная месячная стоимость с НДС
     * @param startDate  начало желаемого периода
     * @param endDate    окончание желаемого периода
     */
    public record MatchRequest(
            PremisesType type,

            @DecimalMin(value = "0.0", message = "Целевая площадь не может быть отрицательной")
            BigDecimal targetArea,

            @DecimalMin(value = "0.0", message = "Минимальная площадь не может быть отрицательной")
            BigDecimal minArea,

            @DecimalMin(value = "0.0", message = "Максимальная площадь не может быть отрицательной")
            BigDecimal maxArea,

            @DecimalMin(value = "0.0", message = "Бюджет не может быть отрицательным")
            BigDecimal maxBudget,

            @NotNull(message = "Дата начала обязательна")
            LocalDate startDate,

            @NotNull(message = "Дата окончания обязательна")
            LocalDate endDate) {
    }

    /**
     * Кандидат в ранжированной выдаче подбора.
     *
     * @param premisesId           идентификатор помещения
     * @param code                 код помещения
     * @param areaSqm              площадь, м²
     * @param estimatedMonthlyCost оценка месячной стоимости с НДС
     * @param score                индекс релевантности 0..100
     * @param rank                 позиция с 1
     */
    public record MatchCandidateResponse(
            Long premisesId,
            String code,
            BigDecimal areaSqm,
            BigDecimal estimatedMonthlyCost,
            BigDecimal score,
            int rank) {
    }

    /**
     * Результат подбора: кандидаты и применённые критерии.
     *
     * @param criteria   исходные критерии
     * @param candidates ранжированный список
     */
    public record MatchResponse(MatchRequest criteria, List<MatchCandidateResponse> candidates) {
    }

    /**
     * Вспомогательный фильтр для ограничения выдачи (не используется в API
     * напрямую, оставлен для документации границ).
     *
     * @param min минимальное значение
     * @param max максимальное значение
     */
    public record RangeFilter(
            @DecimalMin(value = "0.0") BigDecimal min,
            @DecimalMax(value = "1e12") BigDecimal max) {
    }
}
