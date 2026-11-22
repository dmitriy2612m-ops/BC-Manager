package ru.bcmanager.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import ru.bcmanager.domain.enums.ContractStatus;

/**
 * DTO договоров аренды (Plan.md, раздел 5.4).
 */
public final class ContractDtos {

    private ContractDtos() {
    }

    /**
     * Краткая карточка договора для реестра.
     *
     * @param id             идентификатор
     * @param contractNumber номер договора
     * @param premisesId     помещение
     * @param premisesCode   код помещения
     * @param tenantId       арендатор
     * @param tenantName     наименование арендатора
     * @param startDate      дата начала
     * @param endDate        дата окончания
     * @param monthlyRent    месячная плата
     * @param status         статус
     */
    public record ContractSummary(
            Long id,
            String contractNumber,
            Long premisesId,
            String premisesCode,
            Long tenantId,
            String tenantName,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal monthlyRent,
            ContractStatus status) {
    }

    /**
     * Строка графика платежей.
     *
     * @param id            идентификатор платежа
     * @param periodNumber  номер периода
     * @param dueDate       крайняя дата оплаты
     * @param amountDue     сумма к оплате
     * @param paidAmount    оплачено
     * @param paidDate      дата оплаты
     * @param penaltyAmount начисленная пеня
     * @param status        статус платежа
     */
    public record PaymentRow(
            Long id,
            Integer periodNumber,
            LocalDate dueDate,
            BigDecimal amountDue,
            BigDecimal paidAmount,
            LocalDate paidDate,
            BigDecimal penaltyAmount,
            String status) {
    }

    /**
     * Полная карточка договора с графиком.
     *
     * @param summary       краткие данные
     * @param discountPercent применённая скидка, %
     * @param depositAmount обеспечительный платёж
     * @param createdBy     ФИО оформившего сотрудника
     * @param createdAt     дата создания
     * @param payments      график платежей
     */
    public record ContractDetails(
            ContractSummary summary,
            BigDecimal discountPercent,
            BigDecimal depositAmount,
            String createdBy,
            LocalDateTime createdAt,
            List<PaymentRow> payments) {
    }

    /**
     * Запрос предпросмотра договора.
     *
     * @param premisesId        помещение
     * @param startDate         дата начала
     * @param endDate           дата окончания
     * @param excludeContractId договор, который правим (может быть {@code null})
     */
    public record PreviewRequest(
            @NotNull(message = "Помещение обязательно")
            Long premisesId,

            @NotNull(message = "Дата начала обязательна")
            LocalDate startDate,

            @NotNull(message = "Дата окончания обязательна")
            LocalDate endDate,

            Long excludeContractId) {
    }

    /**
     * Результат предпросмотра: расчёт стоимости и график.
     *
     * @param premisesId       помещение
     * @param premisesCode     код помещения
     * @param premisesType     тип помещения
     * @param areaSqm          площадь
     * @param startDate        дата начала
     * @param endDate          дата окончания
     * @param months           количество полных месяцев
     * @param baseMonthlyCost  типизированная ставка (с надбавкой типа)
     * @param opexCost         эксплуатационный сбор
     * @param discountPercent  скидка за длительность, %
     * @param netRent          сумма до НДС
     * @param vatAmount        сумма НДС
     * @param totalMonthly     итоговая месячная плата с НДС
     * @param schedule         предпросмотр графика платежей
     */
    public record PreviewResponse(
            Long premisesId,
            String premisesCode,
            String premisesType,
            BigDecimal areaSqm,
            LocalDate startDate,
            LocalDate endDate,
            int months,
            BigDecimal baseMonthlyCost,
            BigDecimal opexCost,
            BigDecimal discountPercent,
            BigDecimal netRent,
            BigDecimal vatAmount,
            BigDecimal totalMonthly,
            List<ScheduleRow> schedule) {
    }

    /**
     * Строка предпросмотра графика платежей.
     *
     * @param periodNumber номер периода
     * @param dueDate      крайняя дата оплаты
     * @param amountDue    сумма к оплате
     */
    public record ScheduleRow(Integer periodNumber, LocalDate dueDate, BigDecimal amountDue) {
    }

    /**
     * Запрос оформления договора.
     *
     * @param contractNumber номер договора
     * @param tenantId       арендатор
     * @param premisesId     помещение
     * @param startDate      дата начала
     * @param endDate        дата окончания
     * @param activate       активировать сразу (иначе {@code DRAFT})
     */
    public record CreateContractRequest(
            @NotBlank(message = "Номер договора обязателен")
            @Size(max = 40, message = "Номер договора не длиннее 40 символов")
            String contractNumber,

            @NotNull(message = "Арендатор обязателен")
            Long tenantId,

            @NotNull(message = "Помещение обязательно")
            Long premisesId,

            @NotNull(message = "Дата начала обязательна")
            LocalDate startDate,

            @NotNull(message = "Дата окончания обязательна")
            LocalDate endDate,

            boolean activate) {
    }

    /**
     * Запрос смены статуса договора.
     *
     * @param status новый статус
     */
    public record ChangeStatusRequest(
            @NotNull(message = "Статус обязателен")
            ContractStatus status) {
    }
}
