package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellInstrumentBinding;
import ru.company.production.entity.OrderScheduleRow;
import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductType;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.TechProcessOperationControlParam;
import ru.company.production.entity.ZnpKind;
import ru.company.production.repository.AttachedCellView;
import ru.company.production.repository.CellInstrumentBindingRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Привязка ячеек к приборам при проведении цеховой операции.
 *
 * Технолог описывает прибор его входящими ячейками, и в маршруте
 * заказы на приборы (GROUP_DEVICE) стоят контролируемые параметры
 * «Ячейка №» — по виду на каждый классификатор состава. Склад
 * набирает ячейки на весь ЗНП разом (матрица комплектования, V20),
 * и в этой матрице нет ответа на вопрос, КАКОЙ прибор какой ячейкой
 * собран. Ответ даёт исполнитель: он физически вкладывает ячейку в
 * конкретное изделие и обязан зафиксировать это в таблице
 * {@code cell_instrument_bindings} (V91).
 *
 * Почему подпись экземпляра при этом меняется. Для выпуска и
 * группового заказа на ячейки экземпляр плана — изделие из матрицы,
 * и сверять нечего: ячейка у него одна. Для заказа на приборы
 * экземпляр — ПРИБОР, а ячеек у него несколько (по числу видов),
 * поэтому форма показывает не «ячейку экземпляра», а перечень
 * привязок этого прибора: вид, требуемый классификатор и внесённый
 * номер.
 *
 * Проверки привязки держатся здесь, а не в шаблоне: исполнитель
 * вводит номер руками, и принятый без сверки номер либо попал бы в
 * изделие чужим классификатором, либо вошёл бы в два прибора сразу.
 *
 *   1. номер существует в реестре и является именно ячейкой;
 *   2. номер внесён в состав этого заказа-комплектующего (V20) —
 *      «привязка к ЗНП»: ячейка чужого заказа в прибор не входит;
 *   3. классификатор номера совпадает с требуемым параметром;
 *   4. номер ещё не вошёл в другой прибор — один прибор не может
 *      собрать одна и та же ячейка дважды.
 *
 * Замена номера — тот же шаг с отвязкой: прежняя запись вида
 * удаляется, новая создаётся с теми же проверками. Снятие
 * предшествующей привязки нужно сделать явным, иначе уникальный ключ
 * uk_cib_instrument_cell отклонил бы правку нарушением целостности,
 * а исполнитель увидел бы техническую ошибку вместо привычного
 * «изменить номер».
 */
@Service
@RequiredArgsConstructor
public class DeviceCellBindingService {

    /** Ключ вида без классификатора: параметр не задал требуемый вид. */
    private static final Long NO_CLASSIFIER = 0L;

    private final ProductSerialRepository productSerialRepo;
    private final SerialProductionOrderRepository serialLinkRepo;
    private final CellInstrumentBindingRepository bindingRepo;
    private final SerialMovementJournalService journal;

    /**
     * Является ли строка графика работой над прибором.
     *
     * Признак читается у основания строки (заказа-комплектующего), а
     * не выводится из наличия параметров: вид заказа задаёт диспетчер
     * при заведении (V37), и он же определяет, чем подписан экземпляр.
     */
    public boolean isDeviceRow(OrderScheduleRow row) {
        if (row == null) {
            return false;
        }

        ProductionOrder order = row.getProductionOrder();

        return order != null
                && order.getZnpKind() == ZnpKind.GROUP_DEVICE;
    }

    /**
     * Прибор, которому принадлежит экземпляр плана.
     *
     * Сопоставление — по инdex экземпляра: i-й по серии выпущенный
     * прибор закрывает экземпляр с номером i. Тот же порядок держит
     * перечень распределения и «Мои операции», и форма обязана
     * подписывать работу тем же прибором, что показывает список,
     * иначе исполнитель привязал бы ячейку не к тому изделию.
     *
     * null — если прибор ещё не выпущен (серийники заводятся при
     * взятии ЗНП в работу) или индекс вышел за перечень.
     */
    @Transactional(readOnly = true)
    public ProductSerial deviceOf(OrderScheduleRow row) {
        if (!isDeviceRow(row)) {
            return null;
        }

        Integer index = row.getInstanceIndex();
        ProductionOrder order = row.getProductionOrder();

        if (index == null || index < 0 || order.getId() == null) {
            return null;
        }

        List<AttachedCellView> devices = productSerialRepo
                .findDevicesByOrderIds(List.of(order.getId()));

        if (index >= devices.size() || devices.get(index).getSerialId() == null) {
            return null;
        }

        Long instrumentId = devices.get(index).getSerialId();

        return productSerialRepo.findDetailedById(instrumentId).orElse(null);
    }

    /**
     * Привязанные ячейки прибора — по идентификатору параметра.
     *
     * Ключ результата — {@code id} строки параметров маршрута
     * ({@link TechProcessOperationControlParam}): форма рисует их в
     * своём порядке, и ответ обязан приходить тому же параметру, чей
     * вид задан в техпроцессе.
     *
     * Параметр без требуемого классификатора (технолог не выбрал вид)
     * получает привязку, заведённую без вида, — сопоставление идёт по
     * тому же признаку, иначе правка номера потеряла бы прежнюю
     * запись.
     */
    @Transactional(readOnly = true)
    public Map<Long, ProductSerial> boundCellsByParam(
            OrderScheduleRow row,
            List<TechProcessOperationControlParam> links
    ) {
        ProductSerial device = deviceOf(row);

        if (device == null || device.getId() == null
                || row.getProductionOrder() == null || links == null) {
            return Map.of();
        }

        List<CellInstrumentBinding> bindings = bindingRepo
                .findByInstrumentAndOrder(
                        device.getId(),
                        row.getProductionOrder().getId());

        if (bindings.isEmpty()) {
            return Map.of();
        }

        Map<Long, ProductSerial> byClassifier = new HashMap<>();

        for (CellInstrumentBinding binding : bindings) {
            /*
             * Список отсортирован по возрастанию идентификатора,
             * поэтому последней в вид попадает свежая запись — те же
             * правила ряда, что и в перечне привязок заказа.
             */
            byClassifier.put(classifierKey(binding), binding.getCellSerial());
        }

        Map<Long, ProductSerial> result = new HashMap<>();

        for (TechProcessOperationControlParam link : links) {
            ProductSerial cell = byClassifier.get(
                    classifierKeyOf(link.getCellClassifier()));

            if (cell != null) {
                result.put(link.getId(), cell);
            }
        }

        return result;
    }

    /**
     * Привязать ячейку к прибору экземпляра — или заменить номер.
     *
     * Прежняя привязка того же вида снимается до проверки нового
     * номера: исполнитель вправе исправить опечатку, и отказ на
     * занятом виде лишил бы его этой возможности.
     *
     * Возвращается привязанная ячейка — вызывающему нужна её подпись
     * для сообщения, а не повторный запрос к базе.
     */
    @Transactional
    public ProductSerial bind(
            OrderScheduleRow row,
            TechProcessOperationControlParam link,
            String cellNumber,
            AppUser user
    ) {
        if (link == null) {
            throw new IllegalArgumentException("Параметр не найден");
        }

        ProductionOrder order = row == null ? null : row.getProductionOrder();

        if (order == null || order.getId() == null) {
            throw new IllegalStateException(
                    "Строка не относится к заказу-комплектующему"
            );
        }

        ProductSerial device = deviceOf(row);

        if (device == null) {
            throw new IllegalStateException(
                    "Прибор экземпляра ещё не выпущен: привязать ячейку не к чему"
            );
        }

        ProductSerial cell = requireCell(cellNumber, order, device, link);

        ProductSerial previous = boundCellsByParam(row, List.of(link))
                .get(link.getId());

        if (previous != null) {
            if (previous.getId().equals(cell.getId())) {
                throw new IllegalStateException(
                        "Ячейка «" + cell.getFullNumber()
                                + "» уже привязана к прибору «"
                                + device.getFullNumber() + "»"
                );
            }

            unlinkPrevious(row, link, device);
        }

        CellInstrumentBinding binding = new CellInstrumentBinding();

        binding.setInstrumentSerial(device);
        binding.setCellSerial(cell);
        binding.setCellClassifier(link.getCellClassifier());
        binding.setProductionOrder(order);
        binding.setOperation(row.getOperation());
        binding.setCreatedBy(user);

        bindingRepo.save(binding);

        /*
         * Паспорт ячейки обязан показать, что номер вошёл в прибор:
         * до этого события путь номера обрывался на строке выпуска,
         * и по хронологии нельзя было узнать, в какое именно изделие
         * вложили ячейку (V94).
         */
        journal.recordEnteredInstrument(
                cell, device, order, user, LocalDateTime.now());

        return cell;
    }

    /*
     * Снятие прежней привязки вида.
     *
     * Удаляется одна запись — другие виды прибора не затронуты:
     * правка номера трубки не имеет права отвязать от изделия
     * разьём.
     */
    private void unlinkPrevious(
            OrderScheduleRow row,
            TechProcessOperationControlParam link,
            ProductSerial device
    ) {
        Map<Long, ProductSerial> bound = boundCellsByParam(row, List.of(link));

        ProductSerial previous = bound.get(link.getId());

        if (previous == null) {
            return;
        }

        for (CellInstrumentBinding binding
                : bindingRepo.findByInstrumentAndOrder(
                        device.getId(), row.getProductionOrder().getId())) {

            if (previous.getId().equals(binding.getCellSerial().getId())
                    && classifierKey(binding).equals(
                            classifierKeyOf(link.getCellClassifier()))) {
                bindingRepo.deleteById(binding.getId());
            }
        }
    }

    /*
     * Номер ячейки, прошедший все проверки привязки.
     *
     * Порядок проверок объясняет отказ по первой причине: исполнитель
     * обязан понять, что именно не так с введённым номером, а не
     * получить общий текст «привязка невозможна».
     */
    private ProductSerial requireCell(
            String cellNumber,
            ProductionOrder order,
            ProductSerial device,
            TechProcessOperationControlParam link
    ) {
        String number = cellNumber == null ? "" : cellNumber.trim();

        if (number.isEmpty()) {
            throw new IllegalArgumentException(
                    "Укажите номер ячейки, который вошёл в прибор"
            );
        }

        List<ProductSerial> found = productSerialRepo
                .findDetailsByFullNumbers(List.of(number));

        if (found.isEmpty()) {
            throw new IllegalStateException(
                    "Номер «" + number
                            + "» не найден в реестре серийных номеров"
            );
        }

        ProductSerial cell = found.get(0);

        if (cell.getProductType() != ProductType.CELL) {
            throw new IllegalStateException(
                    "Номер «" + number + "» — "
                            + cell.getTypeName()
                            + ", а в прибор привязывается ячейка"
            );
        }

        if (cell.getId().equals(device.getId())) {
            throw new IllegalStateException(
                    "Прибор не может войти в сам себя"
            );
        }

        /*
         * «Привязка к ЗНП»: вносить разрешено только номер из состава
         * этого заказа. Матрица (V20) — единственный источник, знающий,
         * какие ячейки склад оформил именно в этот ЗНП; номер чужого
         * заказа собрал бы прибор из деталей, к нему не относящихся.
         */
        if (!serialLinkRepo.existsBySerial_IdAndProductionOrder_Id(
                cell.getId(), order.getId())) {
            throw new IllegalStateException(
                    "Ячейка «" + number
                            + "» не внесена в состав заказа «"
                            + order.getNumber()
                            + "» — привязать её к прибору нельзя"
            );
        }

        /*
         * Один номер не входит в два прибора одного заказа: иначе
         * вторая привязка собрала бы изделие деталью, которой в нём
         * нет, а исполнитель сверил бы отчёты с фактом. Отказ называет
         * прибор-конкурента, потому что искать его вручную по списку
         * заказа долго и ошибочно.
         */
        ProductSerial rival = deviceOwningCell(cell, order, device);

        if (rival != null) {
            throw new IllegalStateException(
                    "Ячейка «" + number + "» уже привязана к прибору «"
                            + rival.getFullNumber() + "» этого заказа"
            );
        }

        ProductClassifier required = link.getCellClassifier();

        if (required != null && cell.getProductClassifier() != null
                && !required.getId().equals(
                        cell.getProductClassifier().getId())) {
            throw new IllegalStateException(
                    "Ячейка «" + number + "» относится к классификатору «"
                            + labelOf(cell.getProductClassifier())
                            + "», а техпроцесс требует «"
                            + labelOf(required) + "»"
            );
        }

        return cell;
    }

    /*
     * Прибор этого заказа, которому ячейка уже принадлежит.
     *
     * null — номер свободен в рамках заказа. Выдаётся первый из
     * найденных: конкурент обязан быть один (привязка отклоняется на
     * второй попытке), а порядок запроса ставит свежую запись вперёд.
     */
    private ProductSerial deviceOwningCell(
            ProductSerial cell,
            ProductionOrder order,
            ProductSerial device
    ) {
        List<CellInstrumentBinding> rivals = bindingRepo.findRivalBindings(
                cell.getId(), order.getId(), device.getId());

        return rivals.isEmpty() ? null : rivals.get(0).getInstrumentSerial();
    }

    /* Ключ сопоставления привязки с параметром: вид ячейки. */
    private static Long classifierKey(CellInstrumentBinding binding) {
        return binding.getCellClassifier() == null
                ? NO_CLASSIFIER
                : binding.getCellClassifier().getId();
    }

    private static Long classifierKeyOf(ProductClassifier classifier) {
        return classifier == null ? NO_CLASSIFIER : classifier.getId();
    }

    private static String labelOf(ProductClassifier classifier) {
        return classifier == null
                ? "не определён"
                : classifier.getCode() + " — "
                  + classifier.getDisplayName();
    }

    /**
     * Параметры прибора, ожидающие привязки.
     *
     * Нужен проведению: пока хотя бы один вид не внесён, изделие
     * собрано не полностью, и закрывать такую работу нельзя — иначе
     * цех сдал бы прибор без детали, которую требовал техпроцесс.
     */
    public List<TechProcessOperationControlParam> unboundCellParams(
            List<TechProcessOperationControlParam> links,
            Map<Long, ProductSerial> bound
    ) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }

        return links.stream()
                .filter(link -> bound == null
                        || !bound.containsKey(link.getId()))
                .toList();
    }

    /**
     * Привязка обязана существовать — отказ для незакрытого вида.
     *
     * Сообщение перечисляет виды, а не «не все ячейки привязаны»:
     * исполнитель ищет глазами именно тот параметр, к которому не
     * подошёл номер, и по названию вида он находит его быстрее.
     */
    public void requireAllBound(
            List<TechProcessOperationControlParam> cellLinks,
            Map<Long, ProductSerial> bound
    ) {
        List<TechProcessOperationControlParam> missing =
                unboundCellParams(cellLinks, bound);

        if (missing.isEmpty()) {
            return;
        }

        if (missing.size() == cellLinks.size()) {
            throw new IllegalStateException(
                    "В прибор ещё не привязано ни одной ячейки: внесите "
                            + missing.size()
                            + " номер(а) и нажмите «Привязать» — "
                            + "пока все ячейки не привязаны, операцию "
                            + "проводить нельзя"
            );
        }

        throw new IllegalStateException(
                "Не все ячейки привязаны к прибору: " + namesOf(missing)
        );
    }

    /* Названия видов параметров без привязки — для текста отказа. */
    private static String namesOf(
            List<TechProcessOperationControlParam> links
    ) {
        StringBuilder result = new StringBuilder();

        for (TechProcessOperationControlParam link : links) {
            if (result.length() > 0) {
                result.append(", ");
            }

            result.append(link.getControlParam() == null
                    ? "параметр не задан"
                    : link.getControlParam().getName());
        }

        return result.toString();
    }
}