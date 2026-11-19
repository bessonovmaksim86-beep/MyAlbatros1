package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.CellInstrumentBinding;
import ru.company.production.entity.ProductSerial;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Репозиторий для привязки ячеек к приборам (V91).
 */
@Repository
public interface CellInstrumentBindingRepository
        extends JpaRepository<CellInstrumentBinding, Long> {

    /**
     * Привязки ячеек к приборам в рамках заказа.
     *
     * Fetch join'ы закрывают связи, которые читает карточка прибора:
     * номера и наименования прибора и ячейки, вид ячейки и заказ.
     * open-in-view выключен, поэтому без fetch обращение шаблона к
     * ленивой связи упало бы вне транзакции.
     */
    @Query("""
            select b
            from CellInstrumentBinding b
            join fetch b.instrumentSerial instrument
            left join fetch instrument.productClassifier
            join fetch b.cellSerial cell
            left join fetch cell.productClassifier
            left join fetch b.cellClassifier
            join fetch b.productionOrder ord
            where ord.id = :orderId
            order by instrument.serial asc
            """)
    List<CellInstrumentBinding> findByProductionOrderId(
            @Param("orderId") Long orderId
    );

    /**
     * Проверка: привязана ли ячейка к прибору в рамках заказа.
     */
    @Query("""
            select count(b) > 0
            from CellInstrumentBinding b
            where b.instrumentSerial.id = :instrumentSerialId
              and b.cellSerial.id = :cellSerialId
              and b.productionOrder.id = :orderId
            """)
    boolean existsByInstrumentAndCellAndOrder(
            @Param("instrumentSerialId") Long instrumentSerialId,
            @Param("cellSerialId") Long cellSerialId,
            @Param("orderId") Long orderId
    );

    /**
     * Привязки по списку серийников ячеек.
     */
    @Query("""
            select b
            from CellInstrumentBinding b
            join fetch b.instrumentSerial
            join fetch b.cellSerial
            where b.cellSerial.id in :cellIds
            """)
    List<CellInstrumentBinding> findByCellIds(
            @Param("cellIds") Collection<Long> cellIds
    );

    /**
     * Привязки по списку серийников приборов.
     */
    @Query("""
            select b
            from CellInstrumentBinding b
            join fetch b.instrumentSerial
            join fetch b.cellSerial
            where b.instrumentSerial.id in :instrumentIds
            """)
    List<CellInstrumentBinding> findByInstrumentIds(
            @Param("instrumentIds") Collection<Long> instrumentIds
    );

    /**
     * Сколько ячеек привязано к приборам в рамках заказа.
     */
    @Query("""
            select count(b)
            from CellInstrumentBinding b
            where b.productionOrder.id = :orderId
            """)
    long countByProductionOrderId(
            @Param("orderId") Long orderId
    );

    /**
     * Привязки одного прибора заказа — что уже вошло в изделие.
     *
     * Нужен форме проведения: исполнитель видит, ячейка какого вида и
     * каким номером привязана к этому прибору, и вправе заменить
     * номер. Порядок по возрастанию идентификатора: при повторной
     * привязке одного вида последней считается свежая запись, и
     * вызывающий берёт её последней в перечне вида.
     *
     * Fetch join'ы закрывают классификаторы ячейки и вида:
     * open-in-view выключен, а шаблон печатает полный номер и
     * наименование.
     */
    @Query("""
            select b
            from CellInstrumentBinding b
            join fetch b.cellSerial cell
            left join fetch cell.productClassifier
            left join fetch b.cellClassifier
            where b.instrumentSerial.id = :instrumentId
              and b.productionOrder.id = :orderId
            order by b.id asc
            """)
    List<CellInstrumentBinding> findByInstrumentAndOrder(
            @Param("instrumentId") Long instrumentId,
            @Param("orderId") Long orderId
    );

    /**
     * Занята ли ячейка в пределах заказа — привязка к другому прибору.
     *
     * Один номер не может войти в два прибора: без этой проверки
     * привязка удвоила бы ячейку, и второй прибор оказался бы собран
     * чужой деталью. Проверка исключает текущий прибор — так можно
     * проверить занятость перед привязкой, не отклоняя саму запись.
     *
     * Отличие от {@link #existsByCellSerialId}: тот метод смотрит на
     * все привязки в системе (в т.ч. чужих заказов), а здесь нужен
     * контекст именно этого заказа — если номер вышел из другого ЗНП
     * (возврат на склад, отмена заказа), он может быть привязан к
     * чужому прибору, но в этом заказе — свободен.
     */
    @Query("""
            select count(b) > 0
            from CellInstrumentBinding b
            join b.instrumentSerial instrument
            where b.cellSerial.id = :cellSerialId
              and b.productionOrder.id = :orderId
              and instrument.id != :instrumentId
            """)
    boolean existsByCellInOrder(
            @Param("cellSerialId") Long cellSerialId,
            @Param("orderId") Long orderId,
            @Param("instrumentId") Long instrumentId
    );

    /**
     * Привязки этой ячейки в пределах заказа — кроме данного прибора.
     *
     * Нужен отказу «номер уже привязан к прибору …»: сообщение
     * обязано назвать изделие-конкурента, иначе исполнитель искал бы
     * его вручную по перечню приборов заказа. Текущий прибор
     * исключён — иначе проверка отклонила бы собственную запись.
     *
     * Выбираются сами привязки, а не прибор: Hibernate отвергает
     * fetch ассоциации, владелец которой не попал в select list, а
     * вызывающий читает binding.getInstrumentSerial().
     *
     * Порядок — свежие записи вперёд: если номер всё же успел войти
     * в два изделия, назван будет тот, что привязан последним.
     */
    @Query("""
            select b
            from CellInstrumentBinding b
            join fetch b.instrumentSerial instrument
            left join fetch instrument.productClassifier
            where b.cellSerial.id = :cellSerialId
              and b.productionOrder.id = :orderId
              and instrument.id <> :instrumentId
            order by b.id desc
            """)
    List<CellInstrumentBinding> findRivalBindings(
            @Param("cellSerialId") Long cellSerialId,
            @Param("orderId") Long orderId,
            @Param("instrumentId") Long instrumentId
    );

    /**
     * Привязка этой ячейки к этому прибору — для отказа на повторе.
     *
     * Уникальный ключ uk_cib_instrument_cell отклонил бы запись и на
     * уровне базы, но пользователю нужна причина, а не нарушение
     * целостности.
     */
    @Query("""
            select count(b) > 0
            from CellInstrumentBinding b
            where b.instrumentSerial.id = :instrumentSerialId
              and b.cellSerial.id = :cellSerialId
            """)
    boolean existsByInstrumentAndCell(
            @Param("instrumentSerialId") Long instrumentSerialId,
            @Param("cellSerialId") Long cellSerialId
    );

    /**
     * Привязки ячейки — что с ней уже сделано.
     *
     * Нужен отказу: «номер уже привязан к прибору 200100002» обязано
     * назвать прибор, иначе исполнитель искал бы его вручную.
     */
    @Query("""
            select b
            from CellInstrumentBinding b
            join fetch b.instrumentSerial instrument
            left join fetch instrument.productClassifier
            where b.cellSerial.id = :cellSerialId
            order by b.id desc
            """)
    List<CellInstrumentBinding> findByCellSerialId(
            @Param("cellSerialId") Long cellSerialId
    );

    /**
     * Удалить все привязки для заказа.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from CellInstrumentBinding b
            where b.productionOrder.id = :orderId
            """)
    int deleteByProductionOrderId(
            @Param("orderId") Long orderId
    );
}
