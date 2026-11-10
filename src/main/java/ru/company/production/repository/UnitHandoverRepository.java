package ru.company.production.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.UnitHandover;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Репозиторий подтверждаемых передач позиции со склада в подразделение.
 *
 * Путь чтения, вокруг которого всё построено, — незакрытая передача
 * номера в заказ: её ищет проведение операции получения, закрывая
 * перемещение тем же фактом, которым цех закрывает работу по позиции.
 *
 * Второй путь — перечень принятых номеров: по ним реестр наличия
 * склада снимает позицию с полок, потому что лежать на складе и в
 * цехе одновременно она не может.
 *
 * Третий путь — перечень незакрытых номеров: по ним реестр наличия
 * ставит плашку «Передана, ждёт подтверждения», чтобы кладовщик не
 * выдал ту же позицию вторично, пока цех её не подтвердил.
 */
@Repository
public interface UnitHandoverRepository
        extends JpaRepository<UnitHandover, Long> {

    /**
     * Незакрытая передача по идентификатору — с блокировкой строки.
     *
     * Приёмку проводят в один клик, но два мастера одного цеха могут нажать её одновременно с
     * разных экранов. Без блокировки оба увидели бы строку незакрытой и подписали бы получение
     * дважды, а журнал движения показал бы два arrival'а одной позиции.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select handover from UnitHandover handover where handover.id = :id "
            + "and handover.accepted = false and handover.rejected = false")
    Optional<UnitHandover> findPendingForUpdate(@Param("id") Long id);

    /**
     * Номера, принятые подразделениями: их реестр склада больше не
     * показывает.
     *
     * Читают целиком, а не по отобранной странице: реестр наличия
     * строят пакетом, и запрос на каждую строку раздул бы его до
     * сотен обращений к базе.
     */
    @Query("""
            select handover.fullNumber
            from UnitHandover handover
            where handover.accepted = true
            """)
    Set<String> findAcceptedFullNumbers();

    /**
     * Номера, переданные и ещё не закрытые: реестр склада помечает их
     * «Передана, ждёт подтверждения».
     *
     * Отклонённые в перечень не входят: такую позицию склад принял
     * обратно, и она снова лежит на полке.
     */
    @Query("""
            select handover.fullNumber
            from UnitHandover handover
            where handover.accepted = false
              and handover.rejected = false
            """)
    Set<String> findPendingFullNumbers();

    /**
     * Незакрытые передачи конкретной позиции: проверка перед передачей
     * и подпись «куда именно передана» в реестре склада.
     */
    @Query("""
            select handover
            from UnitHandover handover
            where handover.fullNumber in :numbers
              and handover.accepted = false
              and handover.rejected = false
            """)
    List<UnitHandover> findPendingByNumbers(
            @Param("numbers") java.util.Collection<String> numbers);

    /**
     * Незакрытая передача позиции в конкретный заказ: защита от
     * повторной передачи того же номера в тот же заказ.
     */
    boolean existsByFullNumberAndToOrder_IdAndAcceptedFalseAndRejectedFalse(
            String fullNumber, Long orderId);

    /**
     * Последняя передача номера в заказ — в любом состоянии.
     *
     * Отвечает на вопрос «у кого позиция сейчас»: возврат складу не отменяет того, что позицию
     * кому-то везли, поэтому состояние здесь не фильтруется, а берётся последний по созданию
     * шаг — он и есть текущий адрес.
     */
    Optional<UnitHandover> findFirstByFullNumberAndToOrder_IdOrderByIdDesc(
            String fullNumber, Long orderId);

    /**
     * Куда именно ушли позиции реестра: незакрытые передачи порцией идентификаторов.
     *
     * Реестр наличия обязан назвать подразделение, а не просто повесить плашку «ждёт»: кладовщик
     * звонит именно туда, если цех неделями не подтверждает приёмку. Скалярная проекция, а не
     * сущности — строке реестра нужны два значения, и fetch join ради них тянул бы за собой
     * заказы, операции и пользователей.
     */
    @Query("""
            select handover.serial.id as serialId,
                   handover.toUnit.name as unitName
            from UnitHandover handover
            where handover.serial.id in :serialIds
              and handover.accepted = false
              and handover.rejected = false
            """)
    List<HandoverUnitView> findPendingUnitNamesBySerialIds(
            @Param("serialIds") java.util.Collection<Long> serialIds);

    /**
     * Идентификаторов позиций, уже подтверждённых получателями: реестр склада снимает их с полок.
     */
    @Query("""
            select distinct handover.serial.id
            from UnitHandover handover
            where handover.serial.id in :serialIds
              and handover.accepted = true
            """)
    List<Long> findAcceptedSerialIds(
            @Param("serialIds") java.util.Collection<Long> serialIds);

    /**
     * Незакрытая передача номера в заказ: авто-приёмка при проведении
     * операции получения.
     *
     * Ищем именно незакрытую: принятая или отклонённая не подходит,
     * иначе повторное проведение строки приняло бы чужую передачу.
     * Блокировка строки на время транзакции защищает от двойного
     * принятия, если исполнитель проведёт строку дважды (хотя
     * completeRow это предотвращает проверкой completedAt).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select handover from UnitHandover handover "
            + "where handover.fullNumber = :number "
            + "and handover.toOrder.id = :orderId "
            + "and handover.accepted = false "
            + "and handover.rejected = false")
    Optional<UnitHandover> findPendingByNumberAndOrder(
            @Param("number") String number,
            @Param("orderId") Long orderId);

    /** Пара «позиция → подразделение-получатель» для реестра наличия. */
    interface HandoverUnitView {

        Long getSerialId();

        String getUnitName();
    }
}
