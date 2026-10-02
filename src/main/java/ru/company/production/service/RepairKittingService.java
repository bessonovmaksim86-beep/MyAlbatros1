package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.ProductionOrderRepository;

import java.util.List;

/**
 * Комплектование ремонтных ячеек/подсборок из изолятора брака.
 *
 * Страницу смотрит бюро входного контроля, но сами заказы заводит
 * диспетчер: сюда попадают ЗНП на ремонт ячеек и подсборок
 * (V37 — {@link ZnpKind#repairKinds()}), взятые в работу и ведущиеся
 * по техпроцессу, в котором есть комплектовочная операция именно
 * бюро входного контроля.
 *
 * Отбор держится кодов справочников, а не идентификаторов записей:
 * тип «Комплектовочная» заведён миграцией V15, подразделение
 * «Бюро входного контроля» — V30, и оба кода неизменны (у колонок
 * уникальные ключи). Ссылаться на id было бы хуже: id зависят от
 * порядка заполнения базы, а код — от миграции.
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
    private static final String BVK_UNIT_CODE = "Input Control Bureau";

    private final ProductionOrderRepository productionOrderRepository;

    /**
     * ЗНП на ремонт, готовые к комплектованию: статус «В работе» и
     * маршрут с комплектовочной операцией бюро входного контроля.
     */
    @Transactional(readOnly = true)
    public List<ProductionOrder> findRepairKittingOrders() {
        return productionOrderRepository.findRepairKittingOrders(
                ProductionOrderStatus.IN_WORK,
                ZnpKind.repairKinds(),
                OperationTypeCode.KITTING.name(),
                BVK_UNIT_CODE
        );
    }

    /**
     * Число таких заказов — счётчик кнопки обзора и пункта меню.
     */
    @Transactional(readOnly = true)
    public long countRepairKittingOrders() {
        return productionOrderRepository.countRepairKittingOrders(
                ProductionOrderStatus.IN_WORK,
                ZnpKind.repairKinds(),
                OperationTypeCode.KITTING.name(),
                BVK_UNIT_CODE
        );
    }
}
