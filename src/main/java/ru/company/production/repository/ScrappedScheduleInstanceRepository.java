package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.company.production.entity.ScrappedScheduleInstance;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Журнал забракованных экземпляров плана.
 *
 * Хранит привязку «заказ + индекс экземпляра → забракованная позиция»
 * отдельно от матрицы комплектования, чтобы пережить её сжатие: когда
 * ячейка снимается с комплектования (удаление строки
 * serial_production_orders), индексы съезжают, и экземпляр теряет
 * номер. Запись в этой таблице остаётся и позволяет подписать
 * экземпляр как «забраковано», а также исключить его из перечня
 * доступных операций.
 */
public interface ScrappedScheduleInstanceRepository
        extends JpaRepository<ScrappedScheduleInstance, Long> {

    /**
     * Забракованные экземпляры набора заказов — пакетная загрузка для
     * разрешателя внесённых ячеек.
     *
     * Список исполнителя и очередь распределения перечисляют десятки
     * заказов, и запрос на строку дал бы N+1 ровно там, где матрица
     * уже сжата браковкой.
     */
    List<ScrappedScheduleInstance> findByProductionOrderIdIn(
            Collection<Long> productionOrderIds
    );

    /**
     * Все забракованные экземпляры заказ-комплектующего.
     */
    List<ScrappedScheduleInstance> findByProductionOrderId(Long productionOrderId);

    /**
     * Забракованный экземпляр по заказу и индексу.
     */
    Optional<ScrappedScheduleInstance> findByProductionOrderIdAndInstanceIndex(
            Long productionOrderId, int instanceIndex
    );
}
