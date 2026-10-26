package ru.company.production.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.entity.QuarantineTransfer;

import java.util.List;

/**
 * Репозиторий перемещений ячеек из изолятора брака на склад
 * полуфабрикатов.
 *
 * Основной путь чтения — незакрытые перемещения: по ним кладовщик
 * принимает ячейки на склад. Второй путь — перечень уже принятых
 * номеров: по ним изолятор брака убирает ячейку из своего перечня,
 * потому что лежать в изоляторе и на складе одновременно она не
 * может.
 */
@Repository
public interface QuarantineTransferRepository
        extends JpaRepository<QuarantineTransfer, Long> {

    /**
     * Ячейки, которые БВК передал, но склад ещё не принял.
     *
     * Принятые из перечня уходят: приёмку проводят один раз, и
     * оставленная строка подталкивала бы кладовщика провести её
     * повторно. Порядок — по времени перемещения, чтобы склад разбирал
     * очередь сверху вниз.
     *
     * Разновидность ({@code cellKind}) в запросе не отбирают: перечень
     * склада читают по разделам («Подсборки» и «Ячейки»), но незакрытых
     * строк десятки, а не тысячи, поэтому срезку по виду делает сервис.
     * JPQL-проверка {@code :kind is null or ...} над перечислением
     * зависит от вывода типов диалекта, и ровный список с фильтрацией в
     * памяти здесь дешевле расходящегося отбора.
     */
    @Query("""
            select transfer
            from QuarantineTransfer transfer
            left join fetch transfer.transferredBy transferredBy
            left join fetch transfer.productClassifier classifier
            left join fetch transfer.receivingBatch batch
            where transfer.accepted = false
            order by transfer.transferredAt asc, transfer.id asc
            """)
    List<QuarantineTransfer> findPending();

    /**
     * Номера, принятые складом: их изолятор брака больше не показывает.
     *
     * Читают целиком, а не по отобранной странице: перечень изолятора
     * строят пакетом, и запрос на каждую строку раздул бы его до
     * сотен обращений к базе.
     */
    @Query("""
            select transfer.fullNumber
            from QuarantineTransfer transfer
            where transfer.accepted = true
            """)
    List<String> findAcceptedFullNumbers();

    /**
     * Перемещённых ячеек, ожидающих приёмки: общий счётчик склада.
     */
    long countByAcceptedFalse();

    /**
     * Незакрытых перемещений выбранной разновидности.
     *
     * Счётчик висит на пункте своего раздела: общий счётчик под двумя
     * пунктами показал бы кладовщику работу, которой в разделе нет.
     */
    long countByAcceptedFalseAndCellKind(QuarantineCellKind kind);

    /**
     * Все незакрытые номера: проверка перед перемещением.
     *
     * Нужен контроллеру изолятора, чтобы отклонить повторное перемещение
     * одной и той же ячейки.
     */
    @Query("""
            select transfer.fullNumber
            from QuarantineTransfer transfer
            where transfer.accepted = false
            """)
    List<String> findPendingFullNumbers();

    /*
     * ============================================================
     * Жёсткое удаление партии (раздел «Очистка данных»)
     * ============================================================
     */

    /**
     * Перемещения, оформленные на откатываемую партию.
     *
     * Колонка receiving_batch_id ссылается на партию с RESTRICT (V63),
     * поэтому строку либо отвязывают, либо удаляют — третьего вариант
     * база не даёт. Отвязка сохранила бы в изоляторе ссылку на приход,
     * которого больше нет, и раздел показывает эти строки отдельной
     * группой, а не молча снимает отметку.
     */
    @Query("""
            select transfer
            from QuarantineTransfer transfer
            where transfer.receivingBatch.id = :batchId
            order by transfer.transferredAt asc, transfer.id asc
            """)
    List<QuarantineTransfer> findByBatchId(
            @Param("batchId") Long batchId
    );

    long countByReceivingBatch_Id(Long batchId);

    /** Снимает ссылку на партию, строку перемещения сохраняя. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update QuarantineTransfer transfer
            set transfer.receivingBatch = null
            where transfer.receivingBatch.id = :batchId
            """)
    int detachBatch(@Param("batchId") Long batchId);

    /** Удаляет перемещения, оформленные на удаляемую партию. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            delete from QuarantineTransfer transfer
            where transfer.receivingBatch.id = :batchId
            """)
    int deleteByBatchId(@Param("batchId") Long batchId);
}
