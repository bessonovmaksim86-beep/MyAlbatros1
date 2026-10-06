package ru.company.production.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.AssignedOperationView;
import ru.company.production.entity.OperationTypeCode;
import ru.company.production.repository.OrganizationUnitRepository;

import java.util.List;

/**
 * Комплектование склада: операции, которые планировщик создал при
 * планировании заказа покупателя.
 *
 * Разделов два, и оба видны каждой роли склада, потому что склады
 * работают по одному маршруту и различаются только подразделением:
 * склад комплектования собирает ЗНП, склад полуфабрикатов отдаёт
 * полуфабрикаты. Содержимое при этом всегда берётся по подразделению
 * самого раздела, а не по роли вошедшего: кладовщик, открывший чужой
 * раздел, видит работу того склада, на который смотрит, — так реестр
 * складов остаётся читаемым для обоих.
 *
 * Работой склада считаются получение комплектации и комплектовочная
 * операция: их планировщик закрепляет за подразделением, а не за
 * человеком, и цеховые этапы маршрута в раздел не попадают.
 */
@Service
@RequiredArgsConstructor
public class WarehouseKittingService {

    /**
     * Раздел комплектования: его подразделение склада и подписи.
     *
     * Код подразделения — единственный признак границы. Он берётся из
     * справочника, а не из учётной записи вошедшего: раздел обязан
     * показать работу своего склада даже тогда, когда у кладовщика
     * подразделение в карточке не заполнено.
     */
    @Getter
    @RequiredArgsConstructor
    public enum Section {

        /** Склад комплектования собирает заказы-комплектующие (ЗНП). */
        ZNP(
                "ASSEMBLY_WAREHOUSE",
                "Комплектование ЗНП",
                "Заказы-комплектующие склада комплектования: изделия, "
                        + "которые нужно собрать из комплектации выпуска",
                "kitting-znp",
                "warehouse/kitting/znp"
        ),

        /** Склад полуфабрикатов отдаёт полуфабрикаты в сборку. */
        SEMI_FINISHED(
                "SEMI_FINISHED_PRODUCT_WAREHOUSE",
                "Комплектование полуфабрикатов",
                "Полуфабрикаты склада: изделия, которые склад выдаёт "
                        + "в сборку по плану заказа покупателя",
                "kitting-semi",
                "warehouse/kitting/semi"
        );

        private final String unitCode;
        private final String title;
        private final String description;
        private final String activePage;
        private final String view;

        /** Идентификатор раздела для адресной строки. */
        public String param() {
            return this == ZNP ? "znp" : "semi";
        }
    }

    /**
     * Типы операций, составляющие комплектование склада.
     *
     * Получение комплектации — работа по подбору комплектов под
     * выпуск, комплектовочная операция — работа по заказу-комплектующему
     * (V15). Оба типа закреплены планом за складом, поэтому раздел
     * обязан показать и те, и другие: выбор одного из них оставил бы
     * половину работы склада невидимой.
     */
    private static final List<String> KITTING_TYPE_CODES = List.of(
            OperationTypeCode.KIT_RECEIPT.name(),
            OperationTypeCode.KITTING.name()
    );

    private final OrganizationUnitRepository organizationUnitRepository;
    private final WorkshopDistributionService distributionService;

    /**
     * Операции комплектования раздела за горизонт планирования.
     *
     * Пустой список, если подразделение склада в справочнике
     * отсутствует: раздел тогда объясняет отсутствие работы, а не
     * показывает чужую.
     */
    @Transactional(readOnly = true)
    public List<AssignedOperationView> findOperations(Section section) {
        List<Long> unitIds = unitIds(section);

        return unitIds.isEmpty()
                ? List.of()
                : distributionService.findKittingOperations(
                        unitIds, KITTING_TYPE_CODES
                );
    }

    /**
     * Незакрытые операции комплектования раздела — для счётчика меню.
     */
    @Transactional(readOnly = true)
    public long countOperations(Section section) {
        List<Long> unitIds = unitIds(section);

        return unitIds.isEmpty()
                ? 0L
                : distributionService.countKittingOperations(
                        unitIds, KITTING_TYPE_CODES
                );
    }

    /** Раздел по параметру адреса; неизвестное значение — ЗНП. */
    public static Section ofNullable(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase();

            for (Section section : Section.values()) {
                if (section.param().equals(normalized)) {
                    return section;
                }
            }
        }

        return Section.ZNP;
    }

    /*
     * Подразделение склада по коду справочника.
     *
     * Список, а не один идентификатор, — потому что выборка графика
     * отбирает работы по набору подразделений: если в справочнике
     * заведётся второе подразделение того же склада, раздел подхватит
     * его без правки кода.
     */
    private List<Long> unitIds(Section section) {
        return organizationUnitRepository
                .findByCodeIgnoreCase(section.getUnitCode())
                .map(unit -> List.of(unit.getId()))
                .orElse(List.of());
    }
}
