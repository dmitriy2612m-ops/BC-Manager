package ru.bcmanager.dto;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import ru.bcmanager.domain.LeaseContract;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.Tenant;
import ru.bcmanager.domain.premises.Premises;
import ru.bcmanager.service.MaintenanceQueueService;

/**
 * Преобразование доменных сущностей в DTO (Plan.md, раздел 5).
 *
 * <p>Вынесено в отдельный компонент, чтобы контроллеры не содержали
 * логики отображения и оставались тонкими (раздел 8.1). Все методы
 * предполагают, что связанные сущности уже инициализированы в пределах
 * открытой транзакции сервиса.
 */
@Component
public class DtoMapper {

    /**
     * Преобразовать помещение в карточку.
     *
     * @param premises помещение
     * @return DTO карточки
     */
    public PremisesDtos.PremisesResponse toPremises(Premises premises) {
        return new PremisesDtos.PremisesResponse(
                premises.getId(),
                premises.getCode(),
                premises.getBuilding(),
                premises.getFloor(),
                premises.getPremisesType(),
                premises.getAreaSqm(),
                premises.getBaseRatePerSqm(),
                premises.getStatus(),
                premises.isHasFurniture(),
                premises.isHasShowcase(),
                premises.getCapacityPeople(),
                premises.isCovered(),
                premises.getDescription(),
                premises.calculateBaseMonthlyCost(),
                premises.calculateOpexCost());
    }

    /**
     * Преобразовать список помещений.
     *
     * @param premisesList помещения
     * @return список DTO
     */
    public List<PremisesDtos.PremisesResponse> toPremisesList(List<Premises> premisesList) {
        List<PremisesDtos.PremisesResponse> result = new ArrayList<>(premisesList.size());
        for (Premises premises : premisesList) {
            result.add(toPremises(premises));
        }
        return result;
    }

    /**
     * Преобразовать кандидата подбора в DTO.
     *
     * @param candidate кандидат сервиса подбора
     * @return DTO кандидата
     */
    public PremisesDtos.MatchCandidateResponse toCandidate(
            ru.bcmanager.service.SpaceMatchingService.MatchCandidate candidate) {
        return new PremisesDtos.MatchCandidateResponse(
                candidate.premisesId(),
                candidate.code(),
                candidate.areaSqm(),
                candidate.estimatedMonthlyCost(),
                candidate.score(),
                candidate.rank());
    }

    /**
     * Преобразовать арендатора в DTO.
     *
     * @param tenant арендатор
     * @return DTO арендатора
     */
    public TenantDtos.TenantResponse toTenant(Tenant tenant) {
        return new TenantDtos.TenantResponse(
                tenant.getId(),
                tenant.getCompanyName(),
                tenant.getInn(),
                tenant.getContactPerson(),
                tenant.getPhone(),
                tenant.getEmail(),
                tenant.isActive(),
                tenant.getCreatedAt());
    }

    /**
     * Преобразовать договор в краткую карточку.
     *
     * @param contract договор
     * @return DTO краткой карточки
     */
    public ContractDtos.ContractSummary toContractSummary(LeaseContract contract) {
        return new ContractDtos.ContractSummary(
                contract.getId(),
                contract.getContractNumber(),
                contract.getPremises().getId(),
                contract.getPremises().getCode(),
                contract.getTenant().getId(),
                contract.getTenant().getCompanyName(),
                contract.getStartDate(),
                contract.getEndDate(),
                contract.getMonthlyRent(),
                contract.getStatus());
    }

    /**
     * Преобразовать список договоров в краткие карточки.
     *
     * @param contracts договоры
     * @return список DTO
     */
    public List<ContractDtos.ContractSummary> toContractSummaries(List<LeaseContract> contracts) {
        List<ContractDtos.ContractSummary> result = new ArrayList<>(contracts.size());
        for (LeaseContract contract : contracts) {
            result.add(toContractSummary(contract));
        }
        return result;
    }

    /**
     * Преобразовать платёж в строку графика договора.
     *
     * @param payment платёж
     * @return DTO строки графика
     */
    public ContractDtos.PaymentRow toPaymentRow(Payment payment) {
        return new ContractDtos.PaymentRow(
                payment.getId(),
                payment.getPeriodNumber(),
                payment.getDueDate(),
                payment.getAmountDue(),
                payment.getPaidAmount(),
                payment.getPaidDate(),
                payment.getPenaltyAmount(),
                payment.getStatus().name());
    }

    /**
     * Преобразовать платёж в элемент реестра платежей.
     *
     * @param payment платёж
     * @return DTO платежа
     */
    public PaymentDtos.PaymentResponse toPayment(Payment payment) {
        return new PaymentDtos.PaymentResponse(
                payment.getId(),
                payment.getContract().getId(),
                payment.getContract().getContractNumber(),
                payment.getPeriodNumber(),
                payment.getDueDate(),
                payment.getAmountDue(),
                payment.getPaidAmount(),
                payment.getPaidDate(),
                payment.getPenaltyAmount(),
                payment.getStatus().name());
    }

    /**
     * Преобразовать список платежей в реестр.
     *
     * @param payments платежи
     * @return список DTO
     */
    public List<PaymentDtos.PaymentResponse> toPayments(List<Payment> payments) {
        List<PaymentDtos.PaymentResponse> result = new ArrayList<>(payments.size());
        for (Payment payment : payments) {
            result.add(toPayment(payment));
        }
        return result;
    }

    /**
     * Преобразовать заявку в карточку.
     *
     * @param request    заявка
     * @param queueEntry запись очереди или {@code null}, если заявка вне очереди
     * @return DTO заявки
     */
    public MaintenanceDtos.MaintenanceResponse toMaintenance(
            MaintenanceRequest request, MaintenanceQueueService.QueueEntry queueEntry) {
        int position = queueEntry == null ? 0 : queueEntry.queuePosition();
        int effectiveWeight = queueEntry == null
                ? request.getPriority().getBaseWeight()
                : queueEntry.effectiveWeight();
        boolean escalated = queueEntry != null && queueEntry.escalated();
        return new MaintenanceDtos.MaintenanceResponse(
                request.getId(),
                request.getPremises().getId(),
                request.getPremises().getCode(),
                request.getTenant() == null ? null : request.getTenant().getId(),
                request.getTenant() == null ? null : request.getTenant().getCompanyName(),
                request.getTitle(),
                request.getDescription(),
                request.getCategory(),
                request.getPriority(),
                request.getStatus(),
                request.getCreatedAt(),
                request.getResolvedAt(),
                position,
                effectiveWeight,
                escalated);
    }

    /**
     * Преобразовать строку предпросмотра графика.
     *
     * @param entry строка сервиса расчёта
     * @return DTO строки графика
     */
    public ContractDtos.ScheduleRow toScheduleRow(
            ru.bcmanager.service.LeasePricingService.ScheduleEntry entry) {
        return new ContractDtos.ScheduleRow(
                entry.periodNumber(), entry.dueDate(), entry.amountDue());
    }
}
