package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.CustomerOrderItem;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialStatus;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.repository.ProductSerialRepository;

import java.util.Set;

/**
 * Выдача серийных номеров реестра.
 *
 * Номер можно выдать двумя способами:
 *
 *   резервом — под позицию заказа покупателя, когда диспетчер хочет
 *       видеть серийники сразу в заявке, не дожидаясь выпуска;
 *   выпуском — под строку заказа на производство, когда резерва нет.
 *
 * Оба способа считают следующий номер одинаково (см.
 * {@link SerialNumbering}), поэтому расчёт вынесен сюда: два сервиса,
 * считающих ряд независимо, выдали бы один и тот же номер дважды, а
 * уникальный ключ (numbering_key, serial) поймал бы это уже как
 * нарушение целостности.
 */
@Service
@RequiredArgsConstructor
public class SerialIssueService {

    private final ProductSerialRepository productSerialRepository;

    /**
     * Резервирует следующий свободный номер под позицию заказа
     * покупателя.
     *
     * taken — серии, занятые в этой же пачке: позиции заказа выдаются
     * подряд, и номер обязан отличаться от только что выданного, даже
     * если строка ещё не дошла до БД.
     */
    @Transactional
    public ProductSerial reserveForItem(ProductClassifier classifier,
                                        CustomerOrderItem item,
                                        Set<Integer> taken) {

        ProductSerial serial = nextFree(classifier, taken);

        serial.setCustomerOrderItem(item);

        return productSerialRepository.save(serial);
    }

    /**
     * Выдаёт следующий свободный номер строке выпуска.
     *
     * Все номера текущего выпуска уже видны в persistence-контексте,
     * но ещё не записаны, поэтому занятые значения передаёт вызывающий
     * код (см. ProductionOrderService.takenSerials).
     */
    @Transactional
    public ProductSerial issueForRelease(ProductClassifier classifier,
                                         ProductionOrder order,
                                         Set<Integer> taken) {

        ProductSerial serial = nextFree(classifier, taken);

        serial.setIssuedProductionOrder(order);

        return productSerialRepository.save(serial);
    }

    /**
     * Забирает резерв позиции в выпуск.
     *
     * Номер при этом не выдаётся заново: диспетчер и производство
     * видят один и тот же номер, а ряд датчика не получает пустых
     * пропусков там, где изделие уже существует.
     */
    @Transactional
    public ProductSerial claimForRelease(ProductSerial reserved,
                                         ProductionOrder order) {

        reserved.setIssuedProductionOrder(order);

        return productSerialRepository.save(reserved);
    }

    /**
     * Следующая свободная серия области нумерации классификатора.
     *
     * Область — сам классификатор либо род датчика, поэтому максимум
     * берётся по области, а не по типу изделия: 888800122 → 888800123,
     * а у датчиков одного рода ряд сквозной (119200111 → 119400112).
     *
     * Сущность возвращается незаполненной по хозяину: резерв или
     * выпуск — решает вызывающий метод.
     */
    private ProductSerial nextFree(ProductClassifier classifier,
                                   Set<Integer> taken) {

        ProductType productType = classifier.getProductType();

        if (productType == null) {
            throw new IllegalStateException(
                    "Изделию «" + classifier.getCode()
                            + "» не назначен тип продукции"
            );
        }

        String numberingKey = SerialNumbering.keyFor(classifier);

        Integer max = productSerialRepository
                .findMaxSerialByNumberingKey(numberingKey);

        int candidate = (max == null ? 0 : max) + 1;

        Set<Integer> busy = taken == null ? Set.of() : taken;

        while (candidate <= SerialNumbering.MAX_SERIAL
                && (busy.contains(candidate)
                || productSerialRepository
                .existsByNumberingKeyAndSerial(numberingKey, candidate))) {
            candidate++;
        }

        if (candidate > SerialNumbering.MAX_SERIAL) {
            throw new IllegalStateException(
                    "Серии изделия «" + classifier.getCode()
                            + "» исчерпаны: пятизначной нумерации не хватает"
            );
        }

        busy.add(candidate);

        ProductSerial serial = new ProductSerial();

        serial.setProductType(productType);
        serial.setSerial(candidate);
        serial.setProductClassifier(classifier);

        /*
         * Род датчика записывается рядом с номером: для типа SENSOR он
         * и есть область нумерации, а сгенерированный столбец
         * numbering_key считается именно по этой колонке.
         */
        serial.setSensorCatalog(
                productType == ProductType.SENSOR
                        ? classifier.getSensorCatalog()
                        : null
        );

        serial.setStatus(ProductSerialStatus.NEW);

        return serial;
    }
}
