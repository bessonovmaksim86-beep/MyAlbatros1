package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.DeviceZnpAcceptanceRow;
import ru.company.production.dto.InputControlResult;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellInstrumentBinding;
import ru.company.production.entity.ClassifierInclusion;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductionOrderStatus;
import ru.company.production.entity.TechProcess;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.CellInstrumentBindingRepository;
import ru.company.production.repository.ProductClassifierRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.ProductionOrderRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Приёмка групповых ЗНП на приборы — раздел «Приборы».
 *
 * Склад принимает СОБРАННЫЕ приборы: не ячейки (те принимает
 * «Приёмка ячеек»), а изделия типа «Прибор», в которые исполнитель
 * вложил ячейки и зафиксировал привязку в {@code cell_instrument_bindings}
 * (V91). Раздел стоит после комплектации (склад внёс состав) и после
 * цеховой привязки (исполнитель собрал изделие) — он замыкает путь
 * группового ЗНП на приборы: склад сверяет номера приборов и
 * закрепляет их за собой.
 *
 * Прибор считается ГОТОВЫМ К ПРИЁМКЕ, когда в него вошли все виды
 * ячеек, которые технолог заложил в состав прибора: для каждого
 * входящего вида ({@code classifier_inclusion} изделия заказа с типом
 * «Ячейка») у прибора есть привязка этого вида. Пока хоть один вид не
 * привязан, изделие собрано не полностью, и склад его не принимает —
 * иначе кладовщик закрепил бы за складом прибор без детали.
 *
 * Приёмка делает два дела:
 *
 *   * отмечает приборы принятыми на склад (acceptedAt/acceptedBy —
 *       тот же механизм, что у приёмки ячеек, {@link #accept});
 *   * закрывает групповой ЗНП (status → DONE): состав принят целиком,
 *     работать по заказу складу больше нечего.
 *
 * Приёмку проводят один раз: непринятые приборы берут из реестра, а
 * уже принятые повторно не отмечают — иначе журнал заполнился бы
 * дублями ACCEPTED на один прибор.
 */
@Service
@RequiredArgsConstructor
public class DeviceZnpAcceptanceService {

    private final ProductionOrderRepository productionOrderRepository;
    private final ProductSerialRepository serialRepository;
    private final CellInstrumentBindingRepository bindingRepository;
    private final ProductClassifierRepository classifierRepository;
    private final SerialMovementJournalService journal;

    /**
     * Групповые ЗНП на приборы — список раздела.
     *
     * Заказ из списка не убирают, пока в нём есть непринятые приборы:
     * кладовщик обязан видеть, что заказ ждёт сборки, иначе искал бы
     * его вслепую. Кнопку «Принять» даёт признак {@code
     * acceptanceAvailable} — заказ готов, когда каждый его прибор
     * собран полностью и хоть один прибор ещё непринят.
     */
    @Transactional(readOnly = true)
    public List<DeviceZnpAcceptanceRow> findPendingOrders() {

        List<ProductionOrder> orders = productionOrderRepository
                .findGroupDeviceOrdersInWork(ProductionOrderStatus.IN_WORK);

        List<DeviceZnpAcceptanceRow> result = new ArrayList<>();

        for (ProductionOrder order : orders) {

            Readiness readiness = readinessOf(order);

            result.add(new DeviceZnpAcceptanceRow(
                    order,
                    readiness.acceptable(),
                    order.getClassifierLabel(),
                    ProductType.DEVICE.getDisplayName()
            ));
        }

        return result;
    }

    /**
     * Заказы, готовые к приёмке: счётчик пункта меню.
     *
     * Цифра обязана равняться числу кнопок «Принять на склад» в
     * списке, иначе кладовщик шёл бы по счётчику на экран, где
     * провести нечего.
     */
    @Transactional(readOnly = true)
    public long countPending() {
        return findPendingOrders().stream()
                .filter(DeviceZnpAcceptanceRow::acceptanceAvailable)
                .count();
    }

    /**
     * Данные экрана приёмки: заказ, его приборы и привязанные к ним
     * ячейки.
     *
     * Приборы читают перечнем выпуска (i-й по серии прибор), привязки
     * — по каждому прибору. Признак доступности передают и здесь:
     * экран открывают и по прямой ссылке, и без него кладовщик увидел
     * бы кнопку там, где прибор ещё не собран.
     */
    @Transactional(readOnly = true)
    public DeviceZnpAcceptanceView loadView(Long orderId) {

        ProductionOrder order = requireDeviceOrder(orderId);

        List<AttachedCellView> devices = devicesOf(orderId);

        List<ClassifierInclusion> composition = compositionOf(order);
        Set<Long> required = requiredOf(composition);

        List<DeviceRow> rows = new ArrayList<>();

        for (AttachedCellView device : devices) {

            if (device.getSerialId() == null) {
                continue;
            }

            List<CellInstrumentBinding> bindings = bindingRepository
                    .findByInstrumentAndOrder(device.getSerialId(), orderId);

            ProductSerial serial = serialRepository
                    .findDetailedById(device.getSerialId())
                    .orElse(null);

            rows.add(new DeviceRow(
                    device.getSerialId(),
                    device.getFullNumber(),
                    device.getDisplayName(),
                    serial != null && serial.isAccepted(),
                    missingLabels(composition, required, bindings)
            ));
        }

        Readiness readiness = readinessOf(order);

        return new DeviceZnpAcceptanceView(
                order,
                rows,
                techProcessName(order),
                readiness.acceptable()
        );
    }

    /**
     * Проводит приёмку: приборы заказа отмечают принятыми на склад,
     * заказ закрывают.
     *
     * Всё или ничего: принимают все непринятые приборы сразу, и если
     * хоть один прибор собран не полностью — отказ, а не частичная
     * приёмка: склад обязан принять заказ целиком либо не принимать
     * вовсе, иначе закрытый заказ скрыл бы недоделанные изделия.
     */
    @Transactional
    public InputControlResult accept(Long orderId, AppUser receiver) {

        ProductionOrder order = requireDeviceOrder(orderId);

        List<AttachedCellView> devices = devicesOf(orderId);

        if (devices.isEmpty()) {
            return InputControlResult.rejected(
                    "В заказе «" + order.getNumber()
                            + "» ещё не выпущены приборы — принимать нечего"
            );
        }

        Set<Long> required = requiredOf(compositionOf(order));

        List<ProductSerial> pending = new ArrayList<>();
        List<String> incomplete = new ArrayList<>();

        for (AttachedCellView device : devices) {

            if (device.getSerialId() == null) {
                continue;
            }

            ProductSerial serial = serialRepository
                    .findDetailedById(device.getSerialId())
                    .orElse(null);

            if (serial == null || serial.isAccepted()) {
                continue;
            }

            List<CellInstrumentBinding> bindings = bindingRepository
                    .findByInstrumentAndOrder(device.getSerialId(), orderId);

            if (!covers(bindings, required)) {
                incomplete.add(device.getFullNumber());
                continue;
            }

            pending.add(serial);
        }

        if (!incomplete.isEmpty()) {
            return InputControlResult.rejected(
                    "Приборы «" + join(incomplete) + "» собраны не полностью: "
                            + "не все виды ячеек привязаны. Принять заказ "
                            + "целиком нельзя — дождитесь, пока исполнитель "
                            + "привяжет недостающие ячейки"
            );
        }

        if (pending.isEmpty()) {
            return InputControlResult.rejected(
                    "В заказе «" + order.getNumber()
                            + "» нет непринятых приборов — приёмку уже провели"
            );
        }

        LocalDateTime moment = LocalDateTime.now();

        List<Long> ids = pending.stream()
                .map(ProductSerial::getId)
                .toList();

        serialRepository.markAcceptedByIds(ids, moment, receiver);
        journal.recordDeviceAccepted(pending, receiver, moment);

        order.setStatus(ProductionOrderStatus.DONE);
        productionOrderRepository.save(order);

        return InputControlResult.accepted(
                "Принято на склад: " + ids.size() + " приб. по заказу «"
                        + order.getNumber() + "». Заказ закрыт, приборы "
                        + "закреплены за складом"
        );
    }

    /*
     * ============================================================
     * ГОТОВНОСТЬ ЗАКАЗА И ПРИБОРА
     * ============================================================
     */

    /**
     * Готов ли заказ к приёмке: есть непринятые приборы и все приборы
     * собраны полностью.
     *
     * Пустой состав изделия (технолог не задал входящие ячейки) — не
     * готовность, а ошибка маршрута: принимать нечего, и заказ без
     * видов в список с кнопкой не выводят.
     */
    private Readiness readinessOf(ProductionOrder order) {

        if (order.getId() == null) {
            return new Readiness(false);
        }

        List<AttachedCellView> devices = devicesOf(order.getId());

        if (devices.isEmpty()) {
            return new Readiness(false);
        }

        List<ClassifierInclusion> composition = compositionOf(order);
        Set<Long> required = requiredOf(composition);

        if (required.isEmpty()) {
            return new Readiness(false);
        }

        boolean anyPending = false;

        for (AttachedCellView device : devices) {

            if (device.getSerialId() == null) {
                continue;
            }

            ProductSerial serial = serialRepository
                    .findDetailedById(device.getSerialId())
                    .orElse(null);

            if (serial == null) {
                continue;
            }

            if (!serial.isAccepted()) {
                anyPending = true;
            }

            List<CellInstrumentBinding> bindings = bindingRepository
                    .findByInstrumentAndOrder(device.getSerialId(),
                            order.getId());

            if (!covers(bindings, required)) {
                return new Readiness(false);
            }
        }

        return new Readiness(anyPending);
    }

    /**
     * Состав изделия заказа — входящие ячейки, обязанные войти в
     * прибор.
     *
     * Технолог описывает прибор в его карточке ({@code
     * classifier_inclusion}), и именно этот перечень определяет,
     * собран ли прибор. Техпроцесс как источник непригоден: параметры
     * «Ячейка №» висят на цеховой операции, а не на заказе (см.
     * InstrumentKittingService#resolveKinds).
     *
     * Читают один раз на заказ и передают в {@link #requiredOf} и
     * {@link #missingLabels}: повторные обращения к классификатору
     * ради тех же строк состава только множили бы запросы.
     */
    private List<ClassifierInclusion> compositionOf(ProductionOrder order) {

        ProductClassifier instrument = order.getProductClassifier();

        if (instrument == null || instrument.getId() == null) {
            return List.of();
        }

        return classifierRepository
                .findDetailedById(instrument.getId())
                .map(ProductClassifier::getInclusions)
                .orElse(List.of())
                .stream()
                .filter(inclusion -> inclusion.getTarget() != null
                        && inclusion.getTarget().getProductType()
                            == ProductType.CELL)
                .sorted(Comparator.comparing(
                        ClassifierInclusion::getPosition,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Идентификаторы видов ячеек состава — множество требуемых видов. */
    private static Set<Long> requiredOf(List<ClassifierInclusion> composition) {

        Set<Long> required = new HashSet<>();

        for (ClassifierInclusion inclusion : composition) {
            if (inclusion.getTarget().getId() != null) {
                required.add(inclusion.getTarget().getId());
            }
        }

        return required;
    }

    /**
     * Привязки закрывают требуемые виды: у прибора есть привязка
     * каждого вида состава.
     *
     * Вид сверяют по классификатору привязки; привязка без вида
     * (технолог не задал классификатор параметра) требуемый вид не
     * закрывает — её недостаточно, чтобы считать вид внесённым.
     */
    private static boolean covers(
            List<CellInstrumentBinding> bindings,
            Set<Long> required) {

        if (required.isEmpty()) {
            return false;
        }

        Set<Long> bound = new HashSet<>();

        for (CellInstrumentBinding binding : bindings) {
            if (binding.getCellClassifier() != null
                    && binding.getCellClassifier().getId() != null) {
                bound.add(binding.getCellClassifier().getId());
            }
        }

        return bound.containsAll(required);
    }

    /**
     * Подписи непривязанных видов прибора — для экрана приёмки.
     *
     * Кладовщик обязан понять, чего не хватает в изделии, не открывая
     * карточку прибора: перечень названий видов говорит об этом прямо.
     */
    private static List<String> missingLabels(
            List<ClassifierInclusion> composition,
            Set<Long> required,
            List<CellInstrumentBinding> bindings) {

        Set<Long> bound = new HashSet<>();

        for (CellInstrumentBinding binding : bindings) {
            if (binding.getCellClassifier() != null
                    && binding.getCellClassifier().getId() != null) {
                bound.add(binding.getCellClassifier().getId());
            }
        }

        List<String> missing = new ArrayList<>();

        for (ClassifierInclusion inclusion : composition) {

            ProductClassifier cell = inclusion.getTarget();

            if (cell == null || cell.getId() == null
                    || !required.contains(cell.getId())
                    || bound.contains(cell.getId())) {
                continue;
            }

            missing.add(cell.getDisplayName() == null
                    ? String.valueOf(cell.getCode())
                    : cell.getDisplayName());
        }

        return missing;
    }

    /** Приборы заказа — выпущенные серийники типа DEVICE. */
    private List<AttachedCellView> devicesOf(Long orderId) {
        return serialRepository.findDevicesByOrderIds(List.of(orderId));
    }

    /** Заказ обязан быть групповым ЗНП на приборы. */
    private ProductionOrder requireDeviceOrder(Long orderId) {

        ProductionOrder order = productionOrderRepository
                .findDetailedById(orderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Заказ на производство не найден"
                ));

        if (order.getZnpKind() != ZnpKind.GROUP_DEVICE) {
            throw new IllegalStateException(
                    "Приёмка приборов проходит по групповому ЗНП на "
                            + "приборы, а заказ «" + order.getNumber()
                            + "» имеет вид «"
                            + order.getZnpKindDisplayName() + "»"
            );
        }

        return order;
    }

    /** Наименование техпроцесса заказа — текст вместо lazy. */
    private static String techProcessName(ProductionOrder order) {

        TechProcess process = order.getTechProcess();

        if (process == null) {
            return "—";
        }

        StringBuilder label = new StringBuilder();

        if (process.getCode() != null && !process.getCode().isBlank()) {
            label.append(process.getCode());
        }

        if (process.getNote() != null && !process.getNote().isBlank()) {
            if (label.length() > 0) {
                label.append(" — ");
            }
            label.append(process.getNote());
        }

        return label.length() > 0 ? label.toString() : "—";
    }

    /** Короткий перечень номеров в сообщении. */
    private static String join(List<String> numbers) {
        int limit = 8;

        return numbers.size() <= limit
                ? String.join(", ", numbers)
                : String.join(", ", numbers.subList(0, limit)) + " …";
    }

    /** Готовность заказа к приёмке. */
    private record Readiness(boolean acceptable) {
    }

    /**
     * Строка прибора на экране приёмки: номер, принят ли, и чего не
     * хватает.
     *
     * {@code missing} — подписи видов, не привязанных к прибору; пуст
     * у собранного изделия. По нему шаблон красит строку и объясняет
     * кладовщику, почему прибор не принимается.
     */
    public record DeviceRow(
            Long serialId,
            String fullNumber,
            String displayName,
            boolean accepted,
            List<String> missing
    ) {

        public boolean ready() {
            return missing == null || missing.isEmpty();
        }
    }

    /** Данные экрана приёмки. */
    public record DeviceZnpAcceptanceView(
            ProductionOrder order,
            List<DeviceRow> devices,
            String techProcessName,
            boolean acceptanceAvailable
    ) {
    }
}
