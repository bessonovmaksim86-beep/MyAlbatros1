package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.Operation;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Комплектование: заказ-комплектующий (ЗНП) вкладывается в датчики.
 *
 * Нумерация здесь НЕ появляется. Серийный номер выпускает заказ
 * верхнего уровня (ЗП) — он один раз заносится в реестр и больше не
 * меняется. Заказ-комплектующий лишь выбирается из уже выпущенных
 * номеров, поэтому форма ЗНП не создаёт изделия, а предлагает список
 * серийников выбранного выпуска.
 *
 * Связь «многие ко многим» (миграция V20): один ЗНП комплектует
 * группу датчиков, в один датчик входит несколько ЗНП.
 */
@Service
@RequiredArgsConstructor
public class SerialKittingService {

    private final SerialProductionOrderRepository
            serialProductionOrderRepository;

    private final ProductSerialRepository productSerialRepository;

    private final ProductSerialOperationRepository
            productSerialOperationRepository;

    /**
     * Номера, доступные для комплектования: всё, что выпущено
     * выбранным заказом верхнего уровня.
     *
     * Чужие серийники в список не попадают сознательно: комплектующий
     * входит внутрь конкретного выпуска, и выбор номера из другого ЗП
     * приписал бы операцию чужому изделию.
     */
    public List<ProductSerial> findAvailableSerials(Long parentId) {

        if (parentId == null) {
            return List.of();
        }

        return productSerialRepository
                .findDetailsByProductionOrderId(parentId);
    }

    /**
     * Номера датчиков, в которые заказ уже входит — для повторного
     * вывода формы правки.
     */
    @Transactional(readOnly = true)
    public List<Long> findSerialIds(Long productionOrderId) {

        List<Long> ids = new ArrayList<>();

        for (SerialProductionOrder link
                : serialProductionOrderRepository
                .findByProductionOrderId(productionOrderId)) {

            if (link.getSerial() != null) {
                ids.add(link.getSerial().getId());
            }
        }

        return ids;
    }

    /**
     * Состав заказа-комплектующего: датчики, в которые он входит.
     *
     * Детальный запрос: карточка заказа выводит и операцию, а связи
     * ленивые.
     */
    @Transactional(readOnly = true)
    public List<SerialProductionOrder> findKitting(Long productionOrderId) {
        return serialProductionOrderRepository
                .findDetailsByProductionOrderId(productionOrderId);
    }

    /**
     * Привязывает заказ к выбранным датчикам.
     */
    @Transactional
    public void attach(ProductionOrder order,
                       List<Long> serialIds,
                       Operation operation,
                       AppUser author) {

        sync(order, serialIds, operation, author);
    }

    /**
     * Синхронизирует состав: досоздаёт новые связи и снимает снятые.
     *
     * Проверка принадлежности обязательна: форма отдаёт идентификаторы,
     * а их можно передать и вручную. Номер обязан быть выпущен именно
     * тем заказом, который выбран родителем, иначе комплектующий
     * «влез бы» в чужое изделие.
     *
     * Отвязка запрещена, если по связи уже есть записи операций:
     * иначе история выполнения осталась бы висеть на заказе, который
     * к изделию больше не относится.
     */
    @Transactional
    public void sync(ProductionOrder order,
                     List<Long> serialIds,
                     Operation operation,
                     AppUser author) {

        Set<Long> wanted = new LinkedHashSet<>();

        if (serialIds != null) {
            serialIds.stream()
                    .filter(java.util.Objects::nonNull)
                    .forEach(wanted::add);
        }

        if (order.getParentProductionOrder() == null) {
            throw new IllegalStateException(
                    "Заказ «" + order.getNumber()
                            + "» не является комплектующим — "
                            + "датчики привязываются только к нему"
            );
        }

        Long parentId = order.getParentProductionOrder().getId();

        List<SerialProductionOrder> current =
                serialProductionOrderRepository
                        .findDetailsByProductionOrderId(order.getId());

        for (SerialProductionOrder link : current) {

            Long serialId = link.getSerial() == null
                    ? null
                    : link.getSerial().getId();

            if (serialId == null || wanted.contains(serialId)) {
                continue;
            }

            if (productSerialOperationRepository
                    .existsBySerial_IdAndProductionOrder_Id(
                            serialId, order.getId())) {
                throw new IllegalStateException(
                        "Изделие «" + link.getSerial().getFullNumber()
                                + "» уже имеет операции по заказу «"
                                + order.getNumber()
                                + "» — сначала закройте их"
                );
            }

            serialProductionOrderRepository.delete(link);
        }

        if (wanted.isEmpty()) {
            return;
        }

        Set<Long> attached = new LinkedHashSet<>();

        for (SerialProductionOrder link : current) {
            if (link.getSerial() != null) {
                attached.add(link.getSerial().getId());
            }
        }

        for (Long serialId : wanted) {

            if (attached.contains(serialId)) {
                continue;
            }

            ProductSerial serial = productSerialRepository
                    .findById(serialId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Серийный номер не найден"
                    ));

            Long issuedOrderId = serial.getIssuedProductionOrder() == null
                    ? null
                    : serial.getIssuedProductionOrder().getId();

            if (!parentId.equals(issuedOrderId)) {
                throw new IllegalArgumentException(
                        "Датчик «" + serial.getFullNumber()
                                + "» выпущен другим заказом — "
                                + "в комплектующего выбирают номера "
                                + "родительского выпуска"
                );
            }

            SerialProductionOrder link = new SerialProductionOrder();

            link.setSerial(serial);
            link.setProductionOrder(order);
            link.setOperation(operation);
            link.setCreatedBy(author);

            serialProductionOrderRepository.save(link);
        }
    }
}
