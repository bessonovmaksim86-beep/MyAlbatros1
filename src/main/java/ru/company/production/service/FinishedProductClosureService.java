package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.repository.OrderScheduleRowRepository;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.ProductionOrderSerialProjection;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Закрытие заказов-комплектующих (ЗНП) по проведению на СГП.
 *
 * Правило: ЗНП закрывается тогда, когда ВСЕ входящие в него датчики
 * проведены складом готовой продукции. СГП — всегда последний этап
 * маршрута, поэтому его проведение и есть факт «датчик закончен», и
 * другого основания для закрытия у комплектующего нет.
 *
 * Правило одинаково работает для обоих уровней ЗНП:
 *
 *   * верхний (привязан к датчику/номеру) комплектует группу датчиков —
 *     ждёт, пока дойдут все;
 *   * нижний (привязан к операции комплектования датчика) работает над
 *     одним датчиком — для него «все датчики» это один, и он
 *     закрывается тем же вызовом.
 *
 * Дерево вниз отсюда не обходится: у каждого узла свой перечень
 * датчиков и своё основание закрытия, и связь с серийником есть у
 * обоих уровней, поэтому каждый закрывается сам. Каскад остаётся
 * инструментом диспетчера (закрыть выпуск — закрыть всё внутри).
 *
 * Класс вынесен из ProductionOrderService намеренно: тот отвечает за
 * заголовок заказа и выпуск номеров, а здесь — закрытие по внешнему
 * событию цеха. Оба сервиса не зависят друг от друга, поэтому
 * циклической связи нет.
 */
@Service
@RequiredArgsConstructor
public class FinishedProductClosureService {

    /**
     * Код подразделения «склад готовой продукции» (V2).
     *
     * В справочнике он записан без финальной «E» — сверять строго с
     * данными V2. СГП отличается от прочих складов подразделением, а
     * не типом операции, поэтому признак читается именно отсюда.
     */
    public static final String SGP_UNIT_CODE = "FINISHED_PRODUCT_WAREHOUS";

    private final OrderScheduleRowRepository scheduleRowRepository;
    private final ProductionOrderRepository productionOrderRepository;
    private final SerialProductionOrderRepository serialProductionOrderRepository;
    private final WorkshopDistributionSerialService distributionSerialService;

    /**
     * Проведение экземпляра: если его провёл СГП — закрывает ЗНП,
     * дождавшиеся всех своих датчиков.
     *
     * Вызывается сразу после того, как строка графика получила дату
     * факта. Строка читается заново: закрытие опирается на записанный
     * факт, а не на состояние, известное вызывающему коду. Проверка
     * зависимостей маршрута выполнена до проведения, так что СГП-строка
     * действительно последняя у своего экземпляра.
     *
     * Для любого другого подразделения метод ничего не делает — признак
     * СГП проверяется первым и дешёвым.
     */
    @Transactional
    public void onRowCompleted(Long rowId) {

        if (rowId == null) {
            return;
        }

        OrderScheduleRow row =
                scheduleRowRepository.findById(rowId).orElse(null);

        if (!isSgpRow(row)) {
            return;
        }

        CustomerOrder customerOrder = row.getCustomerOrder();

        if (customerOrder == null || customerOrder.getId() == null) {
            return;
        }

        Long serialId = serialIdOf(row);

        /*
         * Серийника нет — выпуска ещё нет, а без номера нельзя ни найти
         * комплектующие этого датчика, ни опознать его среди прочих
         * датчиков группы. Закрыть нечего, выходим молча: ситуация
         * штатная для заказа, который планируется до выпуска.
         */
        if (serialId == null) {
            return;
        }

        List<SerialProductionOrder> entered =
                serialProductionOrderRepository
                        .findDetailsBySerialId(serialId);

        if (entered.isEmpty()) {
            return;
        }

        Set<Long> sgpDone = sgpCompletedSerialIds(customerOrder.getId());

        for (SerialProductionOrder link : entered) {

            ProductionOrder component = link.getProductionOrder();

            if (component == null || isClosed(component.getStatus())) {
                continue;
            }

            if (allSerialsReachedSgp(component.getId(), sgpDone)) {
                component.setStatus(ProductionOrderStatus.DONE);
                productionOrderRepository.save(component);
            }
        }
    }

    /** Экземпляр проводится складом готовой продукции. */
    private boolean isSgpRow(OrderScheduleRow row) {
        return row != null
                && row.getOrganizationUnit() != null
                && SGP_UNIT_CODE.equals(row.getOrganizationUnit().getCode());
    }

    /**
     * Датчик, к которому относится строка графика.
     *
     * Строка плана серийного номера не хранит (V43), поэтому номер
     * достаётся тем же сопоставлением «заказ + позиция + индекс
     * экземпляра → позиция выпуска», что и экран распределения.
     */
    private Long serialIdOf(OrderScheduleRow row) {

        Map<Long, ProductionOrderSerialProjection> resolved =
                distributionSerialService.resolve(List.of(row));

        ProductionOrderSerialProjection projection =
                resolved.get(row.getId());

        return projection == null ? null : projection.getSerialId();
    }

    /**
     * Датчики заказа покупателя, проведённые складом готовой продукции.
     *
     * Факт берётся из проведённых СГП-строк заказа, а серийник к ним
     * подвязывает тот же резолвинг выпуска. Пакетно: один запрос строк
     * и один запрос серийников на заказ, а не по обращению на датчик.
     *
     * Датчик без серийника (выпуск не создан) в множество не попадает,
     * поэтому закрытие откладывается, а не наступает раньше времени:
     * консервативная сторона ошибки предпочтительна — закрытый заказ
     * пришлось бы «открывать» обратно.
     */
    private Set<Long> sgpCompletedSerialIds(Long customerOrderId) {

        List<OrderScheduleRow> rows =
                scheduleRowRepository.findCompletedSgpByCustomerOrder(
                        customerOrderId, SGP_UNIT_CODE);

        if (rows.isEmpty()) {
            return Set.of();
        }

        Set<Long> serialIds = new HashSet<>();

        for (ProductionOrderSerialProjection projection
                : distributionSerialService.resolve(rows).values()) {

            if (projection.getSerialId() != null) {
                serialIds.add(projection.getSerialId());
            }
        }

        return serialIds;
    }

    /**
     * Все ли датчики заказа проведены на СГП.
     *
     * Датчики читаются из связи «заказ ⇄ изделие»: она и есть состав
     * группы. Пустая связь означает, что заказ ни к одному датчику не
     * привязан, — закрывать по СГП такой не за что.
     */
    private boolean allSerialsReachedSgp(
            Long productionOrderId,
            Set<Long> sgpDone
    ) {
        List<SerialProductionOrder> members =
                serialProductionOrderRepository
                        .findByProductionOrderId(productionOrderId);

        if (members.isEmpty()) {
            return false;
        }

        for (SerialProductionOrder member : members) {

            ProductSerial serial = member.getSerial();

            if (serial == null
                    || serial.getId() == null
                    || !sgpDone.contains(serial.getId())) {
                return false;
            }
        }

        return true;
    }

    /** Закрытый статус: его не перезаписывать — это уже запись истории. */
    private static boolean isClosed(ProductionOrderStatus status) {
        return status == ProductionOrderStatus.DONE
                || status == ProductionOrderStatus.CANCELLED;
    }
}
