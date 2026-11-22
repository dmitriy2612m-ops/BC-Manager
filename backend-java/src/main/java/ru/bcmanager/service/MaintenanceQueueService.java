package ru.bcmanager.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import org.springframework.stereotype.Service;
import ru.bcmanager.domain.MaintenanceRequest;
import ru.bcmanager.domain.enums.MaintenanceStatus;

/**
 * Приоритетная очередь заявок на ТО (Plan.md, раздел 4.1, Алгоритм 5).
 *
 * <p>Очередь строится на {@link PriorityQueue} по ключу
 * {@code (effectiveWeight, createdAt, id)}: сначала эффективный вес с учётом
 * SLA-эскалации, затем время поступления и идентификатор для
 * детерминированности. Эскалация реализована в самой сущности
 * {@link MaintenanceRequest} через интерфейс {@code Prioritizable}.
 */
@Service
public class MaintenanceQueueService {

    /**
     * Построить упорядоченную очередь активных заявок.
     *
     * <p>В очередь попадают только заявки в статусах {@code OPEN} и
     * {@code IN_PROGRESS}. Для каждой заявки фиксируются позиция в очереди,
     * эффективный вес и признак эскалации.
     *
     * @param requests активные и закрытые заявки (фильтр внутри)
     * @param now      контрольный момент времени
     * @return заявки в порядке обслуживания
     */
    public List<QueueEntry> prioritize(List<MaintenanceRequest> requests, LocalDateTime now) {
        PriorityQueue<MaintenanceRequest> heap = buildHeap(requests, now);
        List<QueueEntry> queue = new ArrayList<>(heap.size());
        int position = 1;
        while (!heap.isEmpty()) {
            MaintenanceRequest request = heap.poll();
            int weight = request.calculateEffectivePriorityWeight(now);
            queue.add(new QueueEntry(
                    request,
                    position,
                    weight,
                    request.isEscalated(now)));
            position += 1;
        }
        return queue;
    }

    /**
     * Построить двоичную кучу активных заявок.
     *
     * @param requests заявки
     * @param now      контрольный момент времени
     * @return куча с компаратором Алгоритма 5
     */
    public PriorityQueue<MaintenanceRequest> buildHeap(
            List<MaintenanceRequest> requests, LocalDateTime now) {
        PriorityQueue<MaintenanceRequest> heap = new PriorityQueue<>(queueComparator(now));
        for (MaintenanceRequest request : requests) {
            if (request.getStatus().isActive()) {
                heap.add(request);
            }
        }
        return heap;
    }

    /**
     * Добавить одну заявку в существующую очередь ({@code O(log Q)}).
     *
     * @param heap    очередь
     * @param request заявка для добавления
     */
    public void push(PriorityQueue<MaintenanceRequest> heap, MaintenanceRequest request) {
        heap.add(request);
    }

    /**
     * Извлечь следующую заявку из очереди.
     *
     * @param heap очередь
     * @return заявка с наивысшим приоритетом или {@code null}, если очередь пуста
     */
    public MaintenanceRequest poll(PriorityQueue<MaintenanceRequest> heap) {
        return heap.poll();
    }

    /**
     * Компаратор Алгоритма 5 для контрольного момента {@code now}.
     *
     * @param now контрольный момент времени
     * @return компаратор заявок
     */
    public Comparator<MaintenanceRequest> queueComparator(LocalDateTime now) {
        return Comparator
                .comparingInt((MaintenanceRequest request) ->
                        request.calculateEffectivePriorityWeight(now))
                .thenComparing(MaintenanceRequest::getCreatedAt)
                .thenComparing(MaintenanceRequest::getId);
    }

    /**
     * Проверить, участвует ли статус заявки в очереди.
     *
     * @param status статус заявки
     * @return {@code true} для {@code OPEN} и {@code IN_PROGRESS}
     */
    public boolean isQueueStatus(MaintenanceStatus status) {
        return status != null && status.isActive();
    }

    /**
     * Запись приоритетной очереди.
     *
     * @param request         заявка
     * @param queuePosition   позиция в очереди с 1
     * @param effectiveWeight эффективный вес с учётом SLA
     * @param escalated       сработала ли эскалация
     */
    public record QueueEntry(
            MaintenanceRequest request, int queuePosition, int effectiveWeight, boolean escalated) {
    }
}
