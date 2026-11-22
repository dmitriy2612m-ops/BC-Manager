package ru.bcmanager.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.bcmanager.dto.DtoMapper;
import ru.bcmanager.dto.MaintenanceDtos;
import ru.bcmanager.dto.PaymentDtos;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.Payment;
import ru.bcmanager.domain.enums.PaymentStatus;

/**
 * Чтение реестров с отображением в DTO внутри транзакции.
 *
 * <p>{@code open-in-view} выключен, поэтому ленивые связи ({@code Payment.contract},
 * {@code MaintenanceRequest.premises}) нельзя инициализировать в контроллере после
 * завершения транзакции сервиса. Этот фасад собирает DTO там, где сессия Hibernate
 * ещё открыта, и отдаёт контроллерам готовые объекты.
 */
@Service
public class RegistryReadService {

    private final PaymentService paymentService;

    private final MaintenanceService maintenanceService;

    private final DtoMapper mapper;

    /**
     * Создать сервис чтения реестров.
     *
     * @param paymentService     сервис платежей
     * @param maintenanceService сервис заявок
     * @param mapper             преобразователь в DTO
     */
    public RegistryReadService(
            PaymentService paymentService,
            MaintenanceService maintenanceService,
            DtoMapper mapper) {
        this.paymentService = paymentService;
        this.maintenanceService = maintenanceService;
        this.mapper = mapper;
    }

    /**
     * Реестр платежей в виде DTO.
     *
     * @param contractId  договор (может быть {@code null})
     * @param status      статус (может быть {@code null})
     * @param overdueOnly только просроченные
     * @param asOf        контрольная дата расчёта просрочки (может быть {@code null})
     * @return список DTO платежей
     */
    @Transactional(readOnly = true)
    public List<PaymentDtos.PaymentResponse> findPayments(
            Long contractId, PaymentStatus status, boolean overdueOnly, LocalDate asOf) {
        List<Payment> payments =
                paymentService.findAll(contractId, status, overdueOnly, asOf);
        List<PaymentDtos.PaymentResponse> rows = new ArrayList<>(payments.size());
        for (Payment payment : payments) {
            rows.add(mapper.toPayment(payment));
        }
        return rows;
    }

    /**
     * Список заявок в виде DTO.
     *
     * @param prioritized сортировать ли по Алгоритму 5
     * @param controlPoint контрольный момент времени
     * @return список DTO заявок
     */
    @Transactional(readOnly = true)
    public List<MaintenanceDtos.MaintenanceResponse> findMaintenance(
            boolean prioritized, LocalDateTime controlPoint) {
        List<MaintenanceDtos.MaintenanceResponse> rows = new ArrayList<>();

        if (prioritized) {
            for (MaintenanceQueueService.QueueEntry entry
                    : maintenanceService.prioritized(controlPoint)) {
                rows.add(mapper.toMaintenance(entry.request(), entry));
            }
            return rows;
        }

        Map<Long, MaintenanceQueueService.QueueEntry> queueById = new LinkedHashMap<>();
        for (MaintenanceQueueService.QueueEntry entry
                : maintenanceService.prioritized(controlPoint)) {
            queueById.put(entry.request().getId(), entry);
        }
        for (MaintenanceRequest request : maintenanceService.findAll()) {
            rows.add(mapper.toMaintenance(request, queueById.get(request.getId())));
        }
        return rows;
    }
}
