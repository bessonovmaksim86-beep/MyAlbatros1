package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.RepairKittingItem;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.ProductionOrderRepository;
import ru.company.production.repository.RepairKittingItemRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Комплектование ремонтных ячеек/подсборок из изолятора брака.
 *
 * Раздел читают оба бюро контроля — входного (БВК) и технического
 * (БТК): сюда попадают ЗНП на ремонт ячеек и подсборок
 * (V37 — {@link ZnpKind#repairKinds()}), взятые в работу и ведущиеся
 * по техпроцессу, в котором есть комплектовочная операция именно
 * этого бюро. Какое бюро комплектует заказ, решает подразделение
 * операции, а не вид заказа: ремонт ячеек и подсборок одинаков для
 * обоих, различие — в том, чей техпроцесс назначен диспетчером.
 *
 * Отбор держится кодов справочников, а не идентификаторов записей:
 * типы комплектования заведены миграциями V15/V55/V83, подразделения
 * «Бюро входного контроля» — V30 и «Бюро технического контроля» —
 * V2/V66, и все коды неизменны (у колонок уникальные ключи).
 * Ссылаться на id было бы хуже: id зависят от порядка заполнения
 * базы, а код — от миграции.
 *
 * Сервис отдельный от {@link InputControlService}, а не метод в нём:
 * входной контроль оперирует партиями и карточками проверок, а этот
 * раздел читает заказы на производство, и репозиторий у него другой.
 * Смешивание двух агрегатов в одном сервисе сделало бы непонятным,
 * где живут правила проверки партии, а где — правила отбора ЗНП.
 */
@Service
@RequiredArgsConstructor
public class RepairKittingService {

    /**
     * Код подразделения «Бюро входного контроля» (миграция V30).
     *
     * Константа, а не запрос к справочнику: подразделение заведено
     * миграцией, удалять его нельзя (на него ссылается роль
     * BVK_EXECUTOR), а запрос за кодом, который известен из миграции,
     * добавил бы в каждый заход на страницу лишний roundtrip.
     */
    public static final String BVK_UNIT_CODE = "Input Control Bureau";

    /**
     * Код подразделения «Бюро технического контроля» (миграции V2,
     * V66).
     *
     * Тот же мотив, что у {@link #BVK_UNIT_CODE}: код заведён
     * миграцией и неизменен, а на него ссылаются роли BTK_EXECUTOR и
     * BTK_MASTER.
     */
    public static final String BTK_UNIT_CODE = "TECHNICAL_CONTROL_DEPARTMENT";

    /**
     * Признаки комплектовочной операции для ремонтного заказа.
     *
     * Берутся три кода, а не один: диспетчер задаёт ремонтный вид
     * заказа по первой операции маршрута типа «Комплектование ячеек»
     * или «Комплектование подсборок»
     * (ProductionOrderService#resolveKindByTechProcess), а
     * «Комплектовочная» (KITTING) осталась за заказами, заведёнными до
     * V83/V55. Отбор раздела обязан признать все три — иначе страница
     * не нашла бы заказ, который сервис создал как ремонтный, и
     * потеряла бы прежние заказы с общим комплектованием.
     */
    private static final List<String> KITTING_TYPE_CODES = List.of(
            OperationTypeCode.KITTING.name(),
            OperationTypeCode.KITTING_CELL.name(),
            OperationTypeCode.KITTING_SUBASSEMBLY.name()
    );

    private final ProductionOrderRepository productionOrderRepository;
    private final RepairKittingItemRepository itemRepository;

    /**
     * ЗНП на ремонт, готовые к комплектованию бюро входного контроля:
     * статус «В работе» и маршрут с комплектовочной операцией БВК.
     */
    @Transactional(readOnly = true)
    public List<ProductionOrder> findRepairKittingOrders() {
        return findRepairKittingOrders(BVK_UNIT_CODE);
    }

    /**
     * Число таких заказов — счётчик кнопки обзора и пункта меню БВК.
     */
    @Transactional(readOnly = true)
    public long countRepairKittingOrders() {
        return countRepairKittingOrders(BVK_UNIT_CODE);
    }

    /**
     * ЗНП на ремонт, готовые к комплектованию бюро технического
     * контроля: статус «В работе» и маршрут с комплектовочной
     * операцией БТК.
     */
    @Transactional(readOnly = true)
    public List<ProductionOrder> findBtkRepairKittingOrders() {
        return findRepairKittingOrders(BTK_UNIT_CODE);
    }

    /**
     * Число заказов БТК — счётчик кнопки обзора и пункта меню.
     */
    @Transactional(readOnly = true)
    public long countBtkRepairKittingOrders() {
        return countRepairKittingOrders(BTK_UNIT_CODE);
    }

    /**
     * Отбор ремонтных ЗНП по подразделению бюро.
     *
     * Единый источник для обоих бюро: список и счётчик обязаны
     * сходиться между собой и с меню, поэтому различаются только
     * параметром, а не отдельным запросом.
     */
    @Transactional(readOnly = true)
    public List<ProductionOrder> findRepairKittingOrders(String unitCode) {
        return productionOrderRepository.findRepairKittingOrders(
                ProductionOrderStatus.IN_WORK,
                ZnpKind.repairKinds(),
                KITTING_TYPE_CODES,
                List.of(unitCode)
        );
    }

    /**
     * Тот же отбор количеством.
     */
    @Transactional(readOnly = true)
    public long countRepairKittingOrders(String unitCode) {
        return productionOrderRepository.countRepairKittingOrders(
                ProductionOrderStatus.IN_WORK,
                ZnpKind.repairKinds(),
                KITTING_TYPE_CODES,
                List.of(unitCode)
        );
    }

    /**
     * Идентификаторы заказов, у которых состав набран полностью.
     *
     * Признак «Скомплектовано» производный, а не отдельный статус:
     * заказ остаётся «В работе» у диспетчера и в его выборках, но для
     * бюро он закрыт — брать больше нечего. Считать его новым значением
     * {@link ProductionOrderStatus} было бы хуже: заказ выпал бы из
     * перечней и отчётов, где фигурирует {@code IN_WORK}, и из
     * разбора статусов в {@code ProductionOrderService}.
     *
     * Состав читается одним запросом на весь список (тот же пакет, что
     * у меню «Моих операций»): сверка «взято ≥ заказано» на каждую
     * строку дала бы запрос на заказ.
     *
     * Заказ без заданного количества в набор не входит: сверять
     * нечем, и называть такой заказ скомплектованным значило бы
     * закрыть его по признаку, которого в заказе нет.
     */
    @Transactional(readOnly = true)
    public Set<Long> completedOrderIds(List<ProductionOrder> orders) {

        List<Long> ids = orders.stream()
                .map(ProductionOrder::getId)
                .filter(Objects::nonNull)
                .toList();

        if (ids.isEmpty()) {
            return Set.of();
        }

        Map<Long, Long> doneByOrder = new LinkedHashMap<>();

        for (RepairKittingItem item : itemRepository.findByOrderIds(ids)) {

            if (item.getProductionOrder() == null) {
                continue;
            }

            doneByOrder.merge(item.getProductionOrder().getId(), 1L, Long::sum);
        }

        Set<Long> completed = new HashSet<>();

        for (ProductionOrder order : orders) {

            if (order.getId() == null || order.getQuantity() == null) {
                continue;
            }

            long required = order.getQuantity()
                    .setScale(0, RoundingMode.DOWN).longValue();

            if (required > 0
                    && doneByOrder.getOrDefault(order.getId(), 0L) >= required) {
                completed.add(order.getId());
            }
        }

        return completed;
    }
}
