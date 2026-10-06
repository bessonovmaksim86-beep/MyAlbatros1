package ru.company.production.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import ru.company.production.entity.CustomerOrder;
import ru.company.production.entity.CustomerOrderItem;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductType;
import ru.company.production.repository.CustomerOrderRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.service.ProductionOrderService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Предпросмотр состава выпуска строками-экземплярами.
 *
 * Отделён от {@link DispatcherController} намеренно: прежний endpoint
 * {@code customer-order-items} показывал состав агрегированно — строка
 * на позицию заказа («код изделия, количество, экземпляров N»), где
 * экземпляр только подразумевался числом. Диспетчеру в форме нужен
 * перечень самих экземпляров, потому что выпуск — это именно
 * поштучные изделия с серийными номерами, а не строки спецификации.
 *
 * Поэтому здесь одна позиция заказа покупателя разворачивается в
 * столько строк, сколько экземпляров заявлено, и каждая строка несёт
 * ровно то, что видно в цеху над изделием: тип изделия, его серийный
 * номер и полное обозначение.
 *
 * Серийный номер берётся из резерва выбранного заказа покупателя:
 * экземпляр, которому диспетчер уже отметил зарезервированный номер,
 * показывает его; остальным номер выдастся при сохранении, и в
 * предпросмотре их SERIAL-колонка остаётся пустой. Сопоставление
 * резерва с экземплярами идёт по позиции и порядку серии — тот же
 * принцип, по которому состав выпуска строит
 * ProductionOrderService#buildRelease.
 */
@Controller
@RequestMapping("/dispatcher/production-order")
@RequiredArgsConstructor
public class ProductionReleasePreviewController {

    private final CustomerOrderRepository customerOrderRepository;
    private final ProductSerialRepository productSerialRepository;
    private final ProductionOrderService productionOrderService;

    /**
     * Экземпляры выпуска по выбранному заказу покупателя.
     *
     * Ответ — плоский перечень: по одной строке на экземпляр, в порядке
     * позиций заказа и серий внутри позиции. Пустой заказ или пустой
     * выбор дают пустой список — форма покажет свою подсказку.
     */
    @GetMapping("/release-preview")
    @ResponseBody
    public List<Map<String, Object>> releasePreview(
            @RequestParam(required = false) Long customerOrderId
    ) {
        if (customerOrderId == null) {
            return List.of();
        }

        CustomerOrder customerOrder = customerOrderRepository
                .findFullById(customerOrderId)
                .orElse(null);

        if (customerOrder == null) {
            return List.of();
        }

        /*
         * Резерв заказа покупателя, разложенный по позициям и
         * отсортированный по серии: экземпляр с номером n внутри
         * позиции получает n-й по возрастанию зарезервированный номер.
         * Типы ограничены датчиками и системами — остальное выпуску не
         * принадлежит (см. DispatcherController#releaseSerials).
         */
        Map<Integer, List<ProductSerial>> reserved =
                reservedByPosition(customerOrderId);

        List<Map<String, Object>> rows = new ArrayList<>();

        for (CustomerOrderItem item : customerOrder.getItems()) {
            ProductClassifier classifier = item.getProductClassifier();

            if (classifier == null) {
                continue;
            }

            int instances =
                    productionOrderService.instanceCount(item.getQuantity());

            List<ProductSerial> numbers = item.getPosition() == null
                    ? List.of()
                    : reserved.getOrDefault(item.getPosition(), List.of());

            String fullName = fullNameOf(item, classifier);
            String typeName = classifier.getProductType() == null
                    ? "—"
                    : classifier.getProductType().getDisplayName();

            for (int index = 0; index < instances; index++) {
                ProductSerial serial =
                        index < numbers.size() ? numbers.get(index) : null;

                Map<String, Object> row = new LinkedHashMap<>();

                row.put("typeName", typeName);
                row.put("serialNumber",
                        serial == null ? null : serial.getFullNumber());
                row.put("fullName", fullName);
                row.put("serialId", serial == null ? null : serial.getId());
                row.put("itemId", item.getId());

                rows.add(row);
            }
        }

        return rows;
    }

    /**
     * Зарезерированные номера заказа покупателя по его позициям.
     *
     * Ключ — позиция заказа, значение — её номера по возрастанию серии.
     * Номера, уже выпущенные другим заказом или вошедшие в
     * заказ-комплектующий, в перечень не попадают (их отдаёт тот же
     * запрос, что и форму выпуска): предпросмотр обязан показывать
     * ровно те резервы, которые выпуск ещё может взять.
     */
    private Map<Integer, List<ProductSerial>> reservedByPosition(
            Long customerOrderId
    ) {
        List<ProductSerial> serials = productSerialRepository
                .findAssignableReleaseDetailsByCustomerOrderId(
                        customerOrderId,
                        List.of(ProductType.SENSOR, ProductType.SYSTEM)
                );

        Map<Integer, List<ProductSerial>> byPosition = new LinkedHashMap<>();

        for (ProductSerial serial : serials) {
            CustomerOrderItem item = serial.getCustomerOrderItem();

            if (item == null || item.getPosition() == null) {
                continue;
            }

            byPosition
                    .computeIfAbsent(item.getPosition(),
                            key -> new ArrayList<>())
                    .add(serial);
        }

        byPosition.values().forEach(numbers -> numbers.sort(
                Comparator.comparingInt(ProductSerial::getSerial)));

        return byPosition;
    }

    /**
     * Полное обозначение экземпляра: «как в КД» из позиции заказа,
     * а если оно не заполнено — наименование из классификатора.
     */
    private static String fullNameOf(CustomerOrderItem item,
                                     ProductClassifier classifier) {
        String full = item.getFullName();

        if (full != null && !full.isBlank()) {
            return full.trim();
        }

        return classifier.getDisplayName();
    }
}
