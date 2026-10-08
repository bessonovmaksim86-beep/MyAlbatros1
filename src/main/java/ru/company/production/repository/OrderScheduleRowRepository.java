package ru.company.production.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ScheduleRowStatus;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Строки графика заказов покупателя.
 *
 * Запросы построены вокруг двух задач: показать диспетчеру расчёт по
 * заказу и посчитать загрузку подразделений по уже внесённым в план
 * строкам (CONFIRMED). Предварительные строки (DRAFT) в загрузку не
 * входят — иначе черновик, который так и не подтвердили, занял бы
 * людей и отклонил бы следующий заказ.
 *
 * СОБЛЮДЕНИЕ ПО ЗАКАЗУ, СНЯТОМУ С РАБОТЫ.
 * Все запросы цеха и загрузки держат условие по active заказа. Заказ
 * снимается с работы без удаления строки (status = DELETED,
 * active = false), и снятый заказ обязан забрать с собой свой план:
 * иначе руководитель видел бы работу без основания, а планировщик
 * считал бы её занятой. Условие выражено навигацией
 * row.customerOrder.active = true — неявный join отбрасывает и строки,
 * у которых заказа в базе уже нет (физическое удаление). Разовую
 * чистку накопленного делает V47; фильтр нужен, чтобы план не
 * возвращался.
 *
 * Исключения осознанны: findByOrderId, findByCustomerOrderIdAndStatus и
 * deleteByOrderIdAndStatus работают по конкретному заказу — фильтр там
 * только помешал бы снять план с уже снятого заказа, а
 * findRouteRowsOfOrders обслуживает проверку зависимостей перед
 * проведением, где нужны все строки заказа целиком.
 */
@Repository
public interface OrderScheduleRowRepository
        extends JpaRepository<OrderScheduleRow, Long> {

    /**
     * Строки заказа в порядке расчёта: позиция → экземпляр → операция.
     */
    @Query("""
            select row
            from OrderScheduleRow row
            where row.customerOrder.id = :orderId
            order by row.itemPosition asc,
                     row.instanceIndex asc,
                     row.seq asc
            """)
    List<OrderScheduleRow> findByOrderId(@Param("orderId") Long orderId);

    /**
     * Внесённые в план строки заказа по конкретной операции.
     *
     * Нужен проверке при взятии ЗНП в работу: «заказ поставлен в
     * планирование» означает, что по его основанию (заказу покупателя)
     * расчёт внесён в план, то есть строки этой операции есть в статусе
     * CONFIRMED. Строка DRAFT планом не считается — она ничего не занимает
     * и цеху не видна (см. комментарий класса).
     *
     * Навигация по идентификаторам, а не по сущностям: проверка вызывается
     * в момент смены статуса, и разыменовывать lazy-связи незачем.
     */
    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.customerOrder.id = :orderId
              and row.operation.id = :operationId
              and row.status = :status
            """)
    long countConfirmedByOrderAndOperation(
            @Param("orderId") Long orderId,
            @Param("operationId") Long operationId,
            @Param("status") ScheduleRowStatus status
    );

    List<OrderScheduleRow> findByCustomerOrderIdAndStatus(
            Long orderId,
            ScheduleRowStatus status
    );

    /**
     * Внесённые в план строки за период — источник занятого времени
     * для расчёта загрузки. Ограничение диапазоном дат держит выборку
     * в пределах горизонта планирования, а не по всей истории.
     */
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.plannedDate between :from and :to
            """)
    List<OrderScheduleRow> findConfirmedInRange(
            @Param("status") ScheduleRowStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * Занятость этапов чужими заказами: строки перепланируемого заказа
     * исключаются, иначе его же прошлый план удвоил бы загрузку и
     * следующий расчёт уехал бы в бесконечный сдвиг дат.
     */
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.customerOrder.id <> :excludeOrderId
              and row.plannedDate between :from and :to
            """)
    List<OrderScheduleRow> findConfirmedExcludingOrder(
            @Param("status") ScheduleRowStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("excludeOrderId") Long excludeOrderId
    );

    /*
     * Снимает предварительный расчёт перед новым: повторное
     * «Планировать заказ» должно пересобирать черновик, а не копить
     * его поверх старого.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from OrderScheduleRow row
            where row.customerOrder.id = :orderId
              and row.status = :status
            """)
    int deleteByOrderIdAndStatus(
            @Param("orderId") Long orderId,
            @Param("status") ScheduleRowStatus status
    );

    /**
     * Нераспределённые операции подразделения мастера — по датам.
     *
     * Фильтр по work_place/executor пустым: мастер видит только то,
     * что ещё не разложено по рабочим местам.
     */
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.workPlace is null
              and row.executor is null
              and row.plannedDate between :from and :to
            order by row.plannedDate asc, row.itemPosition asc,
                     row.instanceIndex asc, row.seq asc
            """)
    List<OrderScheduleRow> findUnassignedForUnit(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /**
     * Все распределённые и нераспределённые операции подразделения
     * за период — для полной картины загрузки цеха у мастера.
     */
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.plannedDate between :from and :to
            order by row.plannedDate asc, row.itemPosition asc,
                     row.instanceIndex asc, row.seq asc
            """)
    List<OrderScheduleRow> findAllForUnit(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.workPlace is null
              and row.executor is null
            """)
    long countUnassignedForUnit(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId
    );

    /*
     * ============================================================
     * Распределение работ силами подразделения (меню руководителя)
     * ============================================================
     *
     * Планировщик считает даты, но людей не выбирает: колонки
     * executor_id и work_place_id остаются пустыми до решения
     * руководителя. Запросы ниже — его сторона той же схемы V43.
     *
     * Единица распределения — пара «операция + дата плана»: позиция
     * на десять изделий даёт десять строк одной операции в один день,
     * и раскладывать их по людям мастер должен одним решением.
     */

    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "customerOrder",
            "techProcess"
    })
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.executor is null
              and row.operation.operationType.code <> :excludedTypeCode
              and row.plannedDate between :from and :to
            order by row.plannedDate asc,
                     row.operation.operationName.name asc,
                     row.itemPosition asc, row.instanceIndex asc
            """)
    List<OrderScheduleRow> findAssignableForUnit(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("excludedTypeCode") String excludedTypeCode,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * Операции получения комплектации подразделения: они же личные
     * работы руководителя. В очередь распределения не попадают —
     * такую операцию нельзя переложить на исполнителя.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "customerOrder",
            "techProcess"
    })
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.operation.operationType.code = :typeCode
              and row.plannedDate between :from and :to
            order by row.plannedDate asc, row.itemPosition asc,
                     row.instanceIndex asc
            """)
    List<OrderScheduleRow> findKitReceiptForUnit(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("typeCode") String typeCode,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * Строки одной группы распределения — те, что меняют исполнителя
     * разом. Назначение и снятие идут через сущности, а не через
     * bulk-update: у строки есть created_at/updated_at, и обход
     * @PreUpdate оставил бы в истории след, не отвечающий на вопрос
     * «кто и когда закрепил работу».
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "customerOrder"
    })
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.operation.id = :operationId
              and row.plannedDate = :plannedDate
            order by row.itemPosition asc, row.instanceIndex asc
            """)
    List<OrderScheduleRow> findGroupRows(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("operationId") Long operationId,
            @Param("plannedDate") LocalDate plannedDate
    );

    /*
     * Строки одной операции одного изделия — карточка операции внутри
     * раскрытой строки-изделия.
     *
     * От {@link #findGroupRows} запрос отличается позицией: мастер
     * назначает работу над конкретным датчиком, а не над всей операцией
     * дня. Позиция обязательна и в очередь, и при снятии — иначе
     * назначением одного изделия мастер перезаписал бы исполнителей
     * соседних.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "customerOrder"
    })
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.operation.id = :operationId
              and row.plannedDate = :plannedDate
              and row.customerOrder.id = :orderId
              and row.itemPosition = :itemPosition
            order by row.instanceIndex asc
            """)
    List<OrderScheduleRow> findProductOperationRows(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("operationId") Long operationId,
            @Param("plannedDate") LocalDate plannedDate,
            @Param("orderId") Long orderId,
            @Param("itemPosition") Integer itemPosition
    );

    /*
     * Работы, закреплённые за сотрудником — меню «Мои операции».
     *
     * Статус не фильтруется: исполнителю нужны и плановые строки, и
     * уже взятые в работу, иначе начало смены исчезает из вида, а
     * именно по нему человек и идёт в цех.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "customerOrder",
            "techProcess",
            "workPlace"
    })
    @Query("""
            select row
            from OrderScheduleRow row
            where row.executor.id = :executorId
              and row.customerOrder.active = true
              and row.plannedDate between :from and :to
            order by row.plannedDate asc, row.itemPosition asc,
                     row.instanceIndex asc, row.seq asc
            """)
    List<OrderScheduleRow> findForExecutor(
            @Param("executorId") Long executorId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * Строки личных операций руководителя (получение комплектации его
     * подразделения) — вторая половина того же меню «Мои операции».
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "customerOrder",
            "techProcess"
    })
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id in :unitIds
              and row.operation.operationType.code = :typeCode
              and row.plannedDate between :from and :to
            order by row.plannedDate asc, row.itemPosition asc,
                     row.instanceIndex asc
            """)
    List<OrderScheduleRow> findOwnKitReceipt(
            @Param("status") ScheduleRowStatus status,
            @Param("unitIds") java.util.Collection<Long> unitIds,
            @Param("typeCode") String typeCode,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * Операции комплектования подразделения за период — основа
     * разделов «Комплектование ЗНП» и «Комплектование
     * полуфабрикатов».
     *
     * Подразделение здесь задаётся списком, а не одним идентификатором:
     * у склада их бывает несколько (например, склад комплектования
     * ведёт и собственное подразделение, и смежное), и раздел обязан
     * показать работу всего склада, а не одного из его подразделений.
     *
     * Тип операции берётся списком: к комплектованию относятся и
     * комплектовочная операция, и получение комплектации. Остальные
     * этапы маршрута складу не принадлежат, и показывать их в разделе
     * комплектования значило бы смешать разные работы.
     *
     * И назначенные, и неназначенные строки выводятся вместе:
     * кладовщику нужен весь объём комплектования склада, иначе
     * неотобранная работа исчезла бы из вида.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "customerOrder",
            "techProcess",
            "workPlace"
    })
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id in :unitIds
              and row.operation.operationType.code in :typeCodes
              and row.plannedDate between :from and :to
            order by row.plannedDate asc,
                     row.operation.operationName.name asc,
                     row.itemPosition asc, row.instanceIndex asc
            """)
    List<OrderScheduleRow> findKittingForUnits(
            @Param("status") ScheduleRowStatus status,
            @Param("unitIds") java.util.Collection<Long> unitIds,
            @Param("typeCodes") java.util.Collection<String> typeCodes,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /** Строки комплектования подразделения — счётчик в боковом меню. */
    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id in :unitIds
              and row.operation.operationType.code in :typeCodes
              and row.plannedDate between :from and :to
            """)
    long countKittingForUnits(
            @Param("status") ScheduleRowStatus status,
            @Param("unitIds") java.util.Collection<Long> unitIds,
            @Param("typeCodes") java.util.Collection<String> typeCodes,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * Занятость сотрудников по дням — цифры загрузки в списке
     * кандидатов на операцию.
     *
     * Только CONFIRMED: черновик планировщика человеку работу ещё не
     * обещает, и показывать его занятым нельзя. Группировка по паре
     * «сотрудник + день» ровно та же, по какой планировщик считает
     * мощность этапа, — иначе мастер и расчёт видели бы разные цифры.
     */
    @Query("""
            select row.executor.id as executorId,
                   row.plannedDate as plannedDate,
                   sum(row.laborHours) as hours
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.executor.id in :executorIds
              and row.plannedDate between :from and :to
            group by row.executor.id, row.plannedDate
            """)
    List<ExecutorDayLoadView> findExecutorLoad(
            @Param("status") ScheduleRowStatus status,
            @Param("executorIds") java.util.Collection<Long> executorIds,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * Уже распределённые строки подразделения — блок «Распределено»
     * того же раздела: снятие назначения нужно там же, где и
     * назначение, иначе мастер искал бы кнопку в другом месте.
     */
    @EntityGraph(attributePaths = {
            "operation",
            "operation.operationName",
            "operation.operationType",
            "customerOrder",
            "executor"
    })
    @Query("""
            select row
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.executor is not null
              and row.plannedDate between :from and :to
            order by row.plannedDate asc,
                     row.operation.operationName.name asc,
                     row.itemPosition asc, row.instanceIndex asc
            """)
    List<OrderScheduleRow> findAssignedForUnit(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /** Уже распределённые строки подразделения — счётчик в меню. */
    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.executor is not null
              and row.plannedDate between :from and :to
            """)
    long countAssignedForUnit(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /** Нераспределённое без руководящих типов — счётчик в меню. */
    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id = :unitId
              and row.executor is null
              and row.operation.operationType.code <> :excludedTypeCode
              and row.plannedDate between :from and :to
            """)
    long countAssignableForUnit(
            @Param("status") ScheduleRowStatus status,
            @Param("unitId") Long unitId,
            @Param("excludedTypeCode") String excludedTypeCode,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * Счётчики бокового меню. Считаются запросом, а не размером
     * списка: меню рисуется на каждой странице модуля, и собирать
     * ради цифры полный график с исполнителями и экземплярами —
     * платить двумя десятками запросов за каждую загрузку экрана.
     */

    /** Работы, закреплённые за сотрудником — счётчик «Мои операции». */
    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.executor.id = :executorId
              and row.customerOrder.active = true
              and row.plannedDate between :from and :to
            """)
    long countForExecutor(
            @Param("executorId") Long executorId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /** Личные работы руководителя — вторая половина того же счётчика. */
    @Query("""
            select count(row.id)
            from OrderScheduleRow row
            where row.status = :status
              and row.customerOrder.active = true
              and row.organizationUnit.id in :unitIds
              and row.operation.operationType.code = :typeCode
              and row.plannedDate between :from and :to
            """)
    long countOwnKitReceipt(
            @Param("status") ScheduleRowStatus status,
            @Param("unitIds") java.util.Collection<Long> unitIds,
            @Param("typeCode") String typeCode,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /*
     * ============================================================
     * Проверка зависимостей перед проведением операции
     * ============================================================
     *
     * Проводить операцию можно только после предшествующих (V7 —
     * tech_process_operation_deps). Чтобы понять, закрыта ли
     * предшествующая, нужны ВСЕ строки того же заказа по всем его
     * датам: горизонт выборки здесь не работает, иначе работа,
     * запланированная на прошлую неделю и до сих пор не закрытая,
     * выглядела бы выполненной.
     *
     * Пара «техпроцесс + операция» возвращается не одна, а вместе с
     * экземпляром: заказ мог быть перепланирован, и по одной паре
     * висят и закрытая строка плана, и незакрытая строка черновика.
     * Решение принимает сервис. Предшественник считается закрытым для
     * своего экземпляра: строка на поз. 3 · экз. 2 разблокирует
     * именно это изделие, а не всю позицию, иначе закрытие одного
     * изделия открыло бы работу над остальными.
     *
     * Условие по active заказа здесь сознательно НЕ ставится: запрос
     * вызывается по заказу, который проводят прямо сейчас, а фильтр по
     * датам или признаку заказа исказил бы ответ о предшественнике.
     */
    @Query("""
            select row.techProcess.id       as techProcessId,
                   row.operation.id         as operationId,
                   row.customerOrder.number as orderNumber,
                   row.itemPosition         as itemPosition,
                   row.instanceIndex        as instanceIndex,
                   row.completedAt          as completedAt
            from OrderScheduleRow row
            where row.customerOrder.id in :orderIds
            """)
    List<OpenInstanceRowView> findRouteRowsOfOrders(
            @Param("orderIds") java.util.Collection<Long> orderIds
    );

    /*
     * ============================================================
     * Закрытие ЗНП по проведению на СГП
     * ============================================================
     */

    /*
     * Проведённые работы склада готовой продукции по заказу покупателя.
     *
     * СГП — всегда последний этап маршрута, поэтому проведённая строка
     * этого подразделения и есть факт «датчик дошёл до конца». Даты не
     * ограничиваются: закрытию мешает не горизонт плана, а отсутствие
     * факта, и строка прошлого месяца обязана учитываться так же, как
     * сегодняшняя. Подразделение опознаётся по коду справочника (V2), а
     * не по типу операции: СГП отличается от прочего склада именно
     * подразделением.
     *
     * Активный заказ фильтруется наравне с прочими запросами: снятый с
     * работы заказ не должен своим прошлым планом влиять на закрытие.
     */
    @Query("""
            select row
            from OrderScheduleRow row
            where row.customerOrder.id = :customerOrderId
              and row.customerOrder.active = true
              and row.organizationUnit.code = :sgpUnitCode
              and row.completedAt is not null
            """)
    List<OrderScheduleRow> findCompletedSgpByCustomerOrder(
            @Param("customerOrderId") Long customerOrderId,
            @Param("sgpUnitCode") String sgpUnitCode
    );
}
