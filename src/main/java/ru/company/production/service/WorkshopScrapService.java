package ru.company.production.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.CellScrapPreview;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.InputControlDefectReason;
import ru.company.production.entity.Operation;
import ru.company.production.entity.OperationControlParam;
import ru.company.production.entity.PendingDefect;
import ru.company.production.entity.ProductionOrder;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.ProductSerialOperation;
import ru.company.production.entity.ProductSerialOperationStatus;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.entity.SerialProductionOrder;
import ru.company.production.repository.InputControlDefectReasonRepository;
import ru.company.production.repository.OperationControlParamRepository;
import ru.company.production.repository.PendingDefectRepository;
import ru.company.production.repository.ProductSerialOperationRepository;
import ru.company.production.repository.ProductSerialRepository;
import ru.company.production.repository.SerialProductionOrderRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Браковка ячейки/подсборки из производственного цеха.
 *
 * Руководитель цеха создаёт заявку: она попадает в раздел
 * «Отбраковка», где её решает бюро. Решение по заявке живёт НЕ здесь,
 * а в {@link PendingDefectService} — заявка одна и у обоих разделов,
 * и две точки принятия решения разминулись бы по журналу. Здесь остаются
 * три цеховых дела: посмотреть ячейку, проверить и оформить заявку, а
 * когда брак подтверждён — снять ячейку с комплектования ЗНП.
 *
 * Проверки («ячейку вообще можно браковать») отдаёт один метод
 * {@link #problemsOf}: их показывает и предпросмотр формы, и проведение
 * заявки. Экран и проводка обязаны совпадать, иначе форма обещала бы
 * проводку, которую сервер отклонил бы.
 *
 *   1. Ячейка в цеху — есть активная связь {@link SerialProductionOrder}
 *      с действующим заказом. Матрица комплектования и есть ответ
 *      «цех получил эту ячейку от склада»; {@code issuedProductionOrder}
 *      для этого не годится — она отвечает, КЕМ ВЫПУЩЕН номер.
 *
 *   2. Изделие, в которое установлена ячейка, не перемещено из цеха.
 *
 *   3. По ячейке не проведена операция с контролируемым параметром —
 *      отметка {@link OperationControlParam} с номером ячейки в значении
 *      осталась бы висеть над изделием, которого в цеху больше нет.
 *
 * Снятие ЗНП делает заказ незакрытым для комплектовщика: строка связи
 * удаляется, а проведённые операции переводятся в {@code REWORKED} —
 * факт снят, событие в истории осталось. Заказ сразу попадает в
 * счётчик недоукомплектованных, которым склад пользуется для
 * полуфабрикатов, поэтому отдельного признака «нужно доукомплектовать»
 * заводиться не должно.
 */
@Service
@RequiredArgsConstructor
public class WorkshopScrapService {

    /* Минимальная длина номера: 4 цифры кода + 5 цифр серии. */
    private static final int FULL_NUMBER_LENGTH = 9;

    private final ProductSerialRepository serialRepository;
    private final SerialProductionOrderRepository spoRepository;
    private final ProductSerialOperationRepository serialOpRepository;
    private final OperationControlParamRepository ocpRepository;
    private final PendingDefectRepository defectRepository;
    private final InputControlDefectReasonRepository reasonRepository;

    /**
     * Причины брака из справочника БВК — подсказки формы.
     *
     * Цех бракует теми же причинами, что и входной контроль: по ним
     * считают рекламации и статистику брака, и свободный ввод разнёс бы
     * «царапина», «Царапина» и «царапина на корпусе» на три разные
     * строки отчёта. Своя формулировка не запрещена — подсказки лишь
     * помогают выбрать принятое написание.
     */
    @Transactional(readOnly = true)
    public List<InputControlDefectReason> findDefectReasons() {
        return reasonRepository.findAllActiveWithParent();
    }

    /**
     * Заявки цеха на браковку: что уже ждут решения бюро.
     *
     * Нужны форме цеха: руководитель обязан видеть, что на номер
     * заявка уже оформлена, а не получать отказ после нажатия кнопки.
     */
    @Transactional(readOnly = true)
    public List<PendingDefect> findPending() {
        return defectRepository.findPending();
    }

    /**
     * Предпросмотр ячейки при вводе номера.
     *
     * Отделяет «номер неверный» от «с этим номером всё в порядке, но
     * браковать его сейчас нельзя»: мастер видит изделие, ЗНП и
     * разновидность, поэтому понимает, что взял чужую ячейку, ещё до
     * отправки.
     *
     * Изделие здесь не проверяется: мастер вводит его следующим полем,
     * и придираться к пустому номеру форма не вправе.
     */
    @Transactional(readOnly = true)
    public CellScrapPreview preview(String fullNumber) {

        String number = normalize(fullNumber);

        if (number == null || number.length() < FULL_NUMBER_LENGTH) {
            return CellScrapPreview.incomplete(number == null ? "" : number);
        }

        ProductSerial serial = resolve(number);

        if (serial == null) {
            return CellScrapPreview.notFound(number);
        }

        SerialProductionOrder link = activeLink(serial);

        if (link == null) {
            return CellScrapPreview.notInWorkshop(
                    number,
                    List.of(notInWorkshopMessage(number, serial))
            );
        }

        return CellScrapPreview.of(
                number,
                serial.getDisplayLabel(),
                QuarantineCellKind.ofCellReady(serial.isCellReady())
                        .getDisplayName(),
                link.getProductionOrder().getNumber(),
                "Цех: " + unitNameOf(link),
                problemsOf(serial, number, null)
        );
    }

    /**
     * Создать заявку на браковку ячейки или подсборки.
     *
     * Событие в журнал здесь НЕ пишется: заявка — намерение, а не
     * перемещение, и до решения бюро номер обязан числиться там же,
     * где числился.
     *
     * @param fullNumber   номер ячейки/подсборки
     * @param deviceNumber номер изделия, в которое она установлена
     * @param defectReason причина брака — обязательна
     * @param defectStage  этап, на котором брак выявлен
     * @param user         руководитель цеха
     */
    @Transactional
    public PendingDefect createScrapRequest(String fullNumber,
                                           String deviceNumber,
                                           String defectReason,
                                           String defectStage,
                                           AppUser user) {

        String number = normalize(fullNumber);

        if (number == null || number.length() < FULL_NUMBER_LENGTH) {
            throw new IllegalStateException(
                    "Укажите номер ячейки: 9 цифр, код изделия и серия"
            );
        }

        if (defectReason == null || defectReason.isBlank()) {
            throw new IllegalStateException("Укажите причину брака");
        }

        ProductSerial serial = resolve(number);

        if (serial == null) {
            throw new IllegalStateException(
                    "Ячейка " + number + " не найдена в реестре"
            );
        }

        SerialProductionOrder link = activeLink(serial);

        if (link == null) {
            throw new IllegalStateException(
                    notInWorkshopMessage(number, serial)
            );
        }

        List<String> problems = problemsOf(serial, number, deviceNumber);

        if (!problems.isEmpty()) {
            throw new IllegalStateException(problems.get(0));
        }

        PendingDefect defect = new PendingDefect();
        defect.setFullNumber(number);
        defect.setSerial(serial);
        defect.setCellKind(QuarantineCellKind.ofCellReady(serial.isCellReady()));
        defect.setSerialStatus(serial.getStatus());
        defect.setProductClassifier(serial.getProductClassifier());
        defect.setReceivingBatch(serial.getReceivingBatch());
        defect.setCurrentLocation("Цех: " + unitNameOf(link));
        defect.setDefectReason(defectReason.trim());
        defect.setDefectStage(stageLabel(defectStage));
        defect.setRequestedBy(user);
        defect.setDeviceNumber(normalize(deviceNumber));
        defect.setZnpNumber(link.getProductionOrder().getNumber());

        trainReason(defectReason.trim(), user);

        return defectRepository.save(defect);
    }

    /**
     * Тренирует причину: заносит в справочник то написание, которого
     * в нём ещё нет.
     *
     * Требование «причина тренируется с каждой записью»: справочник
     * пополняется по мере работы цеха, и вторая браковка по тому же
     * дефекту выбирает его из подсказок, а не выводит словами заново.
     * Так статистика брака сходится в одну строку, а не рассыпается на
     * синонимы.
     *
     * Причина ищется среди записей верхнего уровня: цех описывает дефект
     * одним оборотом, и заводить его уточнением к чужой группе было бы
     * выдумкой — технологи упорядочивают дерево сами.
     */
    private void trainReason(String reason, AppUser author) {

        if (reasonRepository.findByLevelAndName(null, reason).isPresent()) {
            return;
        }

        InputControlDefectReason trained = new InputControlDefectReason();
        trained.setName(reason);
        trained.setActive(true);
        trained.setCreatedBy(author);

        reasonRepository.save(trained);
    }

    /**
     * Снять ячейку с комплектования ЗНП.
     *
     * Вызывает {@link PendingDefectService}, когда бюро вынесло решение.
     * Делает две вещи:
     *
     *   * снимает факт со всех операций ячейки, проведённых в рамках
     *     этого ЗНП, — строка остаётся в паспорте со статусом
     *     {@code REWORKED} и примечанием, но перестаёт считаться
     *     выполненной;
     *   * удаляет строку матрицы — заказ снова требует эту ячейку,
     *     и склад видит его в счётчике недоукомплектованных.
     *
     * Порядок обратный удалению не случаен: репозиторий матрицы хранит
     * проверку «нельзя снять связь, пока на неё ссылается история
     * выполнения», и факты обязаны быть сняты раньше.
     *
     * Заявки вне реестра (номер прихода) и номера без активной связи
     * пропускаются молча: снимать нечего, а блокировать решение бюро
     * это не вправе.
     */
    @Transactional
    public void releaseZnpQuota(PendingDefect defect, AppUser actor) {

        ProductSerial serial = defect.getSerial();

        if (serial == null) {
            return;
        }

        ProductionOrder order = orderOfLink(serial, defect.getZnpNumber());

        if (order == null) {
            return;
        }

        unwriteOperations(serial, order, actor);

        spoRepository.findBySerial_IdAndProductionOrder_Id(
                        serial.getId(), order.getId())
                .ifPresent(spoRepository::delete);
    }

    /* ============================================================
       ПРОВЕРКИ
       ============================================================
       Каждой отказ обязан назвать причину и что с ней делать:
       «не в цеху» без пояснения заставило бы руководителя искать
       глазами по всем разделам, где сейчас лежит его ячейка.
       ============================================================ */

    /**
     * Что мешает браковать эту ячейку прямо сейчас.
     *
     * Список, а не исключение: предпросмотр обязан показать все
     * помехи разом, а проведение — отказаться по первой из них.
     * Пустой список значит, что заявку примет сервер.
     */
    private List<String> problemsOf(ProductSerial serial,
                                    String number,
                                    String deviceNumber) {

        List<String> problems = new ArrayList<>();

        if (defectRepository.findPendingByFullNumber(number).isPresent()) {
            problems.add("На номер " + number
                    + " уже есть заявка, ожидающая решения бюро");
        }

        String device = normalize(deviceNumber);

        if (device != null) {
            ProductSerial deviceSerial = resolve(device);

            if (deviceSerial == null) {
                problems.add("Изделие " + deviceNumber
                        + " не найдено в реестре");
            } else if (deviceSerial.isAccepted()) {
                problems.add("Изделие " + device
                        + " уже принято на склад — его переместили из цеха. "
                        + "Браковать ячейку можно, пока изделие в цеху");
            }
        }

        for (OperationControlParam fact :
                ocpRepository.findCompletedByCellNumber(number)) {

            if (fact.getControlParam() == null
                    || !fact.getControlParam().isCellMode()) {
                continue;
            }

            problems.add("По ячейке " + number + " проведена операция «"
                    + fact.getControlParam().getName()
                    + "» с контролируемым параметром. "
                    + "Сначала отмените проведение операции");
            break;
        }

        return problems;
    }

    /**
     * Активная связь ячейки с заказом — доказательство, что ячейку
     * этому цеху выдал склад.
     */
    private SerialProductionOrder activeLink(ProductSerial serial) {

        for (SerialProductionOrder link
                : spoRepository.findDetailsBySerialId(serial.getId())) {

            if (link.getProductionOrder() != null
                    && link.getProductionOrder().isActive()) {
                return link;
            }
        }

        return null;
    }

    /**
     * Снимает факт с операций ячейки, проведённых этим заказом.
     *
     * Закрытая попытка превращается в переделку: {@code REWORKED}
     * обязывает {@code actual_date} быть пустым
     * (chk_pso_reworked_has_no_actual), поэтому дата убирается вместе
     * со статусом. Строка не удаляется: цех эту работу выполнял, и в
     * паспорте она обязана читаться.
     */
    private void unwriteOperations(ProductSerial serial,
                                   ProductionOrder order,
                                   AppUser actor) {

        List<ProductSerialOperation> performed = serialOpRepository
                .findBySerialId(serial.getId()).stream()
                .filter(operation -> operation.getActualDate() != null)
                .filter(operation -> operation.getProductionOrder() != null
                        && operation.getProductionOrder().getId()
                                .equals(order.getId()))
                .toList();

        for (ProductSerialOperation operation : performed) {
            operation.setStatus(ProductSerialOperationStatus.REWORKED);
            operation.setActualDate(null);
            operation.setNote(withNote(operation.getNote(),
                    "Операция снята: ячейка забракована в цеху"));
        }

        if (!performed.isEmpty()) {
            serialOpRepository.saveAll(performed);
        }
    }

    /* ============================================================
       СЛУЖЕБНОЕ
       ============================================================ */

    /**
     * Заказ активной связи ячейки.
     *
     * Если заявка несёт номер ЗНП, ищется именно он: за время между
     * заявкой и решением ячейку успели бы перенести в другой заказ,
     * и снимать надо тот, из которого её забраковали. Без номера
     * либо при исчезнувшем заказе берётся первая активная связь.
     */
    private ProductionOrder orderOfLink(ProductSerial serial,
                                        String znpNumber) {

        List<SerialProductionOrder> links =
                spoRepository.findDetailsBySerialId(serial.getId());

        if (znpNumber != null && !znpNumber.isBlank()) {
            for (SerialProductionOrder link : links) {
                ProductionOrder order = link.getProductionOrder();

                if (order != null && znpNumber.equals(order.getNumber())) {
                    return order;
                }
            }

            return null;
        }

        for (SerialProductionOrder link : links) {
            if (link.getProductionOrder() != null
                    && link.getProductionOrder().isActive()) {
                return link.getProductionOrder();
            }
        }

        return null;
    }

    private ProductSerial resolve(String number) {
        return serialRepository
                .findDetailsByFullNumbers(List.of(number)).stream()
                .findFirst()
                .orElse(null);
    }

    /**
     * Подразделение, которому выдан заказ: цех обязан узнать свою
     * ячейку по подписи местоположения.
     */
    private static String unitNameOf(SerialProductionOrder link) {

        Operation operation = link.getOperation();

        if (operation != null
                && operation.getOrganizationUnit() != null
                && operation.getOrganizationUnit().getName() != null) {
            return operation.getOrganizationUnit().getName();
        }

        return link.getProductionOrder().getNumber();
    }

    /**
     * Почему ячейка не в цеху.
     *
     * Отказ обязан подсказать, где её искать: принятая на склад ячейка
     * и невнесённая в заказ — это два разных действия мастера.
     */
    private static String notInWorkshopMessage(String number,
                                               ProductSerial serial) {
        return "Ячейка " + number + " не находится в цеху: "
                + (serial.isAccepted()
                        ? "она принята на склад полуфабрикатов"
                        : "она не внесена ни в один действующий заказ")
                + ". Браковать можно только ячейку, выданную цеху";
    }

    /**
     * Этап отбраковки: цех часто не заполняет его, а колонка обязана
     * быть заполнена — заявка без ответа «где именно» бесполезна
     * для бюро.
     */
    private static String stageLabel(String defectStage) {
        return defectStage == null || defectStage.isBlank()
                ? "Браковка в цеху"
                : defectStage.trim();
    }

    private static String withNote(String existing, String addition) {
        return existing == null || existing.isBlank()
                ? addition
                : existing + " | " + addition;
    }

    /** Номер к поиску: только цифры, по которым он и хранится. */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String digits = value.replaceAll("\\D", "");

        return digits.isEmpty() ? null : digits;
    }
}
