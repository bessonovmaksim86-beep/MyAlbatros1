package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.ProductionOrderRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Счётчики незакрытых дел по всем модулям — для всплывающих
 * уведомлений.
 *
 * Меню каждого модуля печатает свои счётчики серверной отрисовкой, но
 * кладовщик или мастер сидит на одной странице и не видит, что за это
 * время пришла новая работа. Клиент опрашивает один адрес, сравнивает
 * числа с сохранёнными и на прирост показывает всплывающую строку в
 * правом нижнем углу со звуком и переходом в раздел.
 *
 * Граница — учётная запись, а не роль сама по себе: очередь
 * распределения и своя работа считаются по подразделению
 * пользователя ({@link WorkshopStaffService#unitScope}), поэтому
 * руководитель службы видит очередь двух бюро, а мастер цеха — только
 * свой цех. Тот же порядок держат {@code ModuleMenuCountersViewAdvice}
 * и {@code WorkshopViewAdvice}, и расхождение «меню показывает 3,
 * уведомление — 5» читалось бы как ошибка учёта.
 *
 * Разделы, закрытые перед ролью, не считаются вовсе: платой за чужую
 * цифру был бы запрос к чужой таблице каждые тридцать секунд, а
 * уведомление о разделе, куда не пустят, привело бы к 403 по клику —
 * сигнал оказался бы обманкой.
 */
@Service
@RequiredArgsConstructor
public class GlobalNotificationService {

    private final CellReceivingService receivingService;
    private final BvkAcceptanceService acceptanceService;
    private final ZnpAcceptanceService znpAcceptanceService;
    private final QuarantineTransferService quarantineTransferService;
    private final WarehouseKittingService kittingService;
    private final SubassemblyKittingService subassemblyKittingService;
    private final InputControlService inputControlService;
    private final RepairKittingService repairKittingService;
    private final PendingDefectService pendingDefectService;
    private final QualityService qualityService;
    private final WorkshopDistributionService distributionService;
    private final WorkshopStaffService staffService;
    private final ProductionOrderRepository productionOrderRepository;

    /**
     * Счётчики разделов, открытых пользователю.
     *
     * Нулевые в перечень не попадают: уведомление показывает только то,
     * что действительно ждёт человека. Обнуление клиент видит по общему
     * снимку — ключ пропавшего раздела он трактует как «дел нет».
     */
    @Transactional(readOnly = true)
    public List<CounterItem> collectActive(AppUser user) {

        if (user == null) {
            return List.of();
        }

        List<CounterItem> result = new ArrayList<>();

        addOwnOperations(result, user);

        if (ModuleRoutes.canOpen(user.getRole(), "/dispatcher/group-znp")) {
            addDispatcherCounters(result);
        }

        if (ModuleRoutes.canOpen(user.getRole(), "/warehouse/receiving")) {
            addWarehouseCounters(result);
        }

        if (ModuleRoutes.canOpen(user.getRole(), "/input-control/general")) {
            addInputControlCounters(result);
        }

        if (ModuleRoutes.canOpen(user.getRole(), "/quality/orders")) {
            result.add(new CounterItem(
                    "quality-open-orders",
                    "Открытые заказы на ремонт",
                    "/quality/orders",
                    qualityService.countOpenOrders()
            ));
        }

        if (distributionService.isSupervisor(user)) {
            addDistributionQueue(result, user);
        }

        return result.stream()
                .filter(item -> item.count() > 0)
                .toList();
    }

    /*
     * Своя незакрытая работа — общая для всех ролей.
     *
     * Пункт «Мои операции» есть в меню каждого интерфейса, и исполнитель
     * живёт им больше, чем прочими разделами: закреплённую операцию он
     * обязан увидеть сразу, иначе она простоит в очереди до конца смены.
     */
    private void addOwnOperations(List<CounterItem> result, AppUser user) {

        long total = distributionService.countMyOperations(user)
                + distributionService.countMyGroupOperations(user);

        result.add(new CounterItem(
                "my-operations",
                "Мои операции",
                "/my-operations",
                total
        ));
    }

    /*
     * Диспетчерский отдел: заказы в работе.
     *
     * Заказчики и приоритеты в перечень не входят — это справочники, а
     * не очередь: число там меняется только когда кто-то сам что-то
     * заводит, и сигнал о справочнике был бы шумом.
     */
    private void addDispatcherCounters(List<CounterItem> result) {

        result.add(new CounterItem(
                "dispatcher-production-orders",
                "Заказы на производство",
                "/dispatcher/production-order",
                productionOrderRepository.countActiveByKinds(
                        ZnpKind.productionOrderKinds()
                )
        ));

        /*
         * Счётчик «Групповые ЗНП» убран: эти заказы живут по своей
         * логике (не привязаны к выпуску), и общее число активных
         * заказов вводит диспетчера в заблуждение.
         */
    }

    /*
     * Склад полуфабрикатов: приёмка и комплектование.
     *
     * Набор повторяет пункты меню склада — сигнал приходит по тем же
     * разделам, чьи цифры кладовщик видит в боковом меню.
     */
    private void addWarehouseCounters(List<CounterItem> result) {

        result.add(new CounterItem(
                "warehouse-receiving",
                "Оприходование ячеек",
                "/warehouse/receiving",
                receivingService.countPending()
        ));

        result.add(new CounterItem(
                "warehouse-acceptance",
                "Приёмка с БВК",
                "/warehouse/acceptance",
                acceptanceService.countAwaiting()
        ));

        result.add(new CounterItem(
                "warehouse-quarantine-transfer",
                "Перемещение из изолятора",
                "/warehouse/quarantine-transfer",
                quarantineTransferService.countPending()
        ));

        result.add(new CounterItem(
                "warehouse-znp-acceptance",
                "Приёмка ячеек",
                "/warehouse/znp-acceptance",
                znpAcceptanceService.countPending()
        ));

        result.add(new CounterItem(
                "warehouse-subassembly-kitting",
                "Комплектование подсборок",
                "/warehouse/subassembly-kitting",
                subassemblyKittingService.countPending()
        ));

        result.add(new CounterItem(
                "warehouse-kitting-znp",
                "Комплектование ЗНП",
                "/warehouse/kitting-znp",
                kittingService.countOperations(
                        WarehouseKittingService.Section.ZNP
                )
        ));

        result.add(new CounterItem(
                "warehouse-kitting-semi",
                "Комплектование полуфабрикатов",
                "/warehouse/kitting-semi",
                kittingService.countOperations(
                        WarehouseKittingService.Section.SEMI_FINISHED
                )
        ));
    }

    /** Бюро входного контроля: карточки контроля, изолятор, отбраковка. */
    private void addInputControlCounters(List<CounterItem> result) {

        result.add(new CounterItem(
                "bvk-awaiting",
                "Новые заявки входного контроля",
                "/input-control/control",
                inputControlService.countAwaiting()
        ));

        result.add(new CounterItem(
                "bvk-in-work",
                "Входной контроль в работе",
                "/input-control/control",
                inputControlService.countInWork()
        ));

        result.add(new CounterItem(
                "bvk-return",
                "Возврат поставщику",
                "/input-control/returns",
                inputControlService.countReturns()
        ));

        result.add(new CounterItem(
                "bvk-quarantine",
                "Изолятор брака",
                "/input-control/quarantine",
                inputControlService.countQuarantine()
        ));

        result.add(new CounterItem(
                "bvk-repair-kitting",
                "Комплектование на ремонт",
                "/input-control/repair-kitting",
                repairKittingService.countRepairKittingOrders()
        ));

        /*
         * Заявки на отбраковку живут вне /input-control/**: адрес открыт
         * обеим ролям бюро отдельным правилом SecurityConfig, и решение
         * по заявке руководитель несёт наравне с исполнителем.
         */
        result.add(new CounterItem(
                "bvk-pending-defects",
                "Заявки на отбраковку",
                "/pending-defects",
                pendingDefectService.countPending()
        ));
    }

    /*
     * Очередь распределения работ.
     *
     * Считается по границе подразделений руководителя — той же, что
     * держит раздел (см. ModuleMenuCountersViewAdvice): руководитель
     * службы качества отвечает за БТК и БВК сразу, и цифра по одному
     * подразделению не сошлась бы с очередью, которую он увидит.
     */
    private void addDistributionQueue(List<CounterItem> result, AppUser user) {

        long queue = distributionService.countAssignable(
                staffService.unitScope(user)
        );

        result.add(new CounterItem(
                "workshop-distribution",
                "Не распределено работ",
                "/workshop/distribution",
                queue
        ));
    }

    /**
     * Пункт уведомления.
     *
     * @param key   идентификатор раздела — ключ снимка в localStorage;
     *              переименовывать его нельзя: у клиентов, сохранивших
     *              старый ключ, счётчик «исчезнет», и следующий опрос
     *              подаст сигнал по всем делам сразу
     * @param label наименование раздела для строки уведомления
     * @param url   адрес раздела: клик по строке переводит в него
     * @param count сколько дел ждёт адресата
     */
    public record CounterItem(String key, String label, String url, long count) {
    }
}
