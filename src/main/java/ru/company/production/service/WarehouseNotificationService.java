package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Счётчики незакрытых дел склада полуфабрикатов — единый источник для
 * бокового меню и для уведомлений.
 *
 * До появления этого класса каждый счётчик вызывался из двух мест:
 * {@link ru.company.production.controller.WarehouseViewAdvice} считал
 * их для бейджей меню, а экрану уведомлений требовались те же числа —
 * и второй вызов удваивал обращения к базе на каждой странице модуля.
 * Здесь счётчики считаются один раз и отдаются списком, где у каждого
 * пункта есть адрес раздела.
 *
 * Порядок перечня повторяет порядок пунктов меню: блок уведомлений
 * читается так же, как меню, и искать глазами «где тот пункт» не
 * приходится.
 */
@Service
@RequiredArgsConstructor
public class WarehouseNotificationService {

    private final CellReceivingService receivingService;
    private final BvkAcceptanceService acceptanceService;
    private final ZnpAcceptanceService znpAcceptanceService;
    private final QuarantineTransferService quarantineTransferService;
    private final WarehouseKittingService kittingService;
    private final SubassemblyKittingService subassemblyKittingService;

    /**
     * Все пункты с их счётчиками — включая нулевые.
     *
     * Нулевые не отбрасываются: меню печатает бейдж только при
     * положительном счётчике, и ему нужен весь перечень, чтобы
     * сопоставить пункт с числом.
     */
    @Transactional(readOnly = true)
    public List<CounterItem> collectAll() {

        List<CounterItem> items = new ArrayList<>();

        items.add(new CounterItem(
                "receiving",
                "Оприходование ячеек",
                "/warehouse/receiving",
                receivingService.countPending()
        ));

        items.add(new CounterItem(
                "acceptance",
                "Приёмка с БВК",
                "/warehouse/acceptance",
                acceptanceService.countAwaiting()
        ));

        items.add(new CounterItem(
                "quarantine-transfer",
                "Перемещение из изолятора",
                "/warehouse/quarantine-transfer",
                quarantineTransferService.countPending()
        ));

        items.add(new CounterItem(
                "znp-acceptance",
                "Приёмка ячеек",
                "/warehouse/znp-acceptance",
                znpAcceptanceService.countPending()
        ));

        items.add(new CounterItem(
                "subassembly-kitting",
                "Комплектование подсборок",
                "/warehouse/subassembly-kitting",
                subassemblyKittingService.countPending()
        ));

        items.add(new CounterItem(
                "kitting-znp",
                "Комплектование ЗНП",
                "/warehouse/kitting-znp",
                kittingService.countOperations(
                        WarehouseKittingService.Section.ZNP)
        ));

        items.add(new CounterItem(
                "kitting-semi",
                "Комплектование полуфабрикатов",
                "/warehouse/kitting-semi",
                kittingService.countOperations(
                        WarehouseKittingService.Section.SEMI_FINISHED)
        ));

        return items;
    }

    /**
     * Пункты, по которым есть незакрытые дела.
     *
     * Это содержимое блока уведомлений: пустой список означает, что
     * показывать нечего и блок в сайдбаре не появляется.
     */
    @Transactional(readOnly = true)
    public List<CounterItem> collectActive() {

        return collectAll().stream()
                .filter(item -> item.count() > 0)
                .toList();
    }

    /**
     * Пункт склада с количеством незакрытых дел.
     *
     * @param key   идентификатор раздела — он же значение
     *              {@code activePage} в шаблонах и ключ сравнения в
     *              {@code localStorage} на клиенте
     * @param title наименование раздела для строки уведомления
     * @param url   адрес раздела: клик по строке переводит в него
     * @param count сколько дел ждёт кладовщика
     */
    public record CounterItem(String key, String title, String url, long count) {
    }
}
