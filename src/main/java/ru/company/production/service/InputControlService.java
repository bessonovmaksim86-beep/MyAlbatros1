package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.InputControlForm;
import ru.company.production.dto.InputControlResult;
import ru.company.production.dto.QuarantineFilter;
import ru.company.production.dto.QuarantineReasonGroup;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.entity.InputControlCard;
import ru.company.production.entity.InputControlCardCell;
import ru.company.production.entity.InputControlCardReason;
import ru.company.production.entity.InputControlCellType;
import ru.company.production.entity.InputControlDecision;
import ru.company.production.entity.InputControlDefectGroup;
import ru.company.production.entity.InputControlDefectReason;
import ru.company.production.entity.InputControlStatus;
import ru.company.production.entity.InputControlType;
import ru.company.production.entity.ProductSerial;
import ru.company.production.entity.QuarantineCellKind;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.InputControlCardCellRepository;
import ru.company.production.repository.InputControlCardReasonRepository;
import ru.company.production.repository.InputControlCardRepository;
import ru.company.production.repository.InputControlDefectGroupRepository;
import ru.company.production.repository.InputControlDefectReasonRepository;
import ru.company.production.repository.ProductSerialRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Бюро входного контроля оприходованных партий.
 *
 * Партия попадает сюда после того, как склад закрыл приход
 * (confirmed_at у {@link CellReceivingBatch}): её реквизиты —
 * поставщик, ордер, изменение, диапазон номеров — и есть карточка
 * оприходования, с которой начинает проверку контролёр.
 *
 * Проверка выборочная, и это определяет всю логику сверки:
 * внесённые номера обязаны принадлежать партии, но их заведомо может
 * быть меньше, чем ячеек в партии. Отметка «все ячейки» заменяет
 * перечень полным диапазоном партии.
 *
 * Брак описывают группами: ячейки с одним дефектом и одним исходом
 * ({@link InputControlDefectGroup}). Партию бракуют частями — часть
 * возвращают поставщику по одному дефекту, часть оставляют в изоляторе
 * брака по другому, — поэтому решение относится к группе, а не к
 * партии целиком.
 *
 * Брак не существует без причины: номера группы без выбранной причины
 * отклоняются, потому что ячейки без причины нельзя ни списать, ни
 * заявить поставщику. Причина, в свою очередь, требует решения.
 *
 * Статусы ячеек в общем реестре при этом не меняются: перечень
 * забракованных номеров хранится в карточке. Перевод ячейки в
 * «изолятор брака» отдельным статусом реестра — вопрос к ограничению
 * product_serials.status, он требует отдельной миграции и решения о
 * том, как такой статус переживает дальнейшие операции.
 */
@Service
@RequiredArgsConstructor
public class InputControlService {

    /** Длина полного номера: 4 цифры кода + 5 цифры серии. */
    private static final int FULL_NUMBER_LENGTH = 9;

    /** Номеров в сообщении об ошибке: длинный список не читаем. */
    private static final int MESSAGE_NUMBER_LIMIT = 12;

    private final InputControlCardRepository cardRepository;
    private final InputControlCardCellRepository cellRepository;
    private final InputControlCardReasonRepository reasonRepository;
    private final InputControlDefectGroupRepository groupRepository;
    private final InputControlDefectReasonRepository defectReasonRepository;
    private final CellReceivingBatchRepository batchRepository;
    private final ProductSerialRepository serialRepository;

    /**
     * Партии, ожидающие проверки: оприходованные складом.
     *
     * Проверенную партию из перечня не убирают: карточку закрывают,
     * а партия остаётся в списке с пометкой о завершённой проверке.
     * Убранная партия терялась бы из вида — контролёр не мог бы ни
     * сверить решение, ни увидеть, что партию уже проверяли.
     */
    @Transactional(readOnly = true)
    public List<CellReceivingBatch> findReceivedBatches() {
        return batchRepository.findAllDetailed().stream()
                .filter(batch -> !batch.isPending())
                .toList();
    }

    /**
     * Состояние входного контроля партии для списка.
     *
     * Список партий показывает не только приход, но и то, взята ли
     * партия в работу и не закрыта ли уже проверка — иначе контролёр
     * не отличит свою незавершённую карточку от новой партии.
     */
    @Transactional(readOnly = true)
    public Map<Long, InputControlCard> findOpenCardsByBatch() {
        return cardsByBatch(InputControlStatus.IN_WORK);
    }

    /**
     * Завершённые проверки партий: по ним список даёт ссылку «Смотреть».
     *
     * Если партию после закрытия снова взяли в работу, её карточка
     * попадает в {@link #findOpenCardsByBatch()} — открытую проверку
     * показывают, а не закрытую.
     */
    @Transactional(readOnly = true)
    public Map<Long, InputControlCard> findCompletedCardsByBatch() {
        return cardsByBatch(InputControlStatus.COMPLETED);
    }

    /**
     * Карточки заданного статуса по партиям: по партии оставляют
     * самую свежую — запрос уже отсортирован, поэтому достаточно
     * первого вхождения.
     */
    private Map<Long, InputControlCard> cardsByBatch(InputControlStatus status) {
        Map<Long, InputControlCard> cardsByBatch = new LinkedHashMap<>();

        cardRepository.findAllDetailed().stream()
                .filter(card -> card.getStatus() == status)
                .forEach(card -> cardsByBatch.putIfAbsent(
                        card.getBatch().getId(),
                        card
                ));

        return cardsByBatch;
    }

    /** Карточек в работе — счётчик в боковом меню. */
    @Transactional(readOnly = true)
    public long countInWork() {
        return cardRepository.countByStatus(InputControlStatus.IN_WORK);
    }

    /** Проверок закрыто — сводка главной страницы модуля. */
    @Transactional(readOnly = true)
    public long countCompleted() {
        return cardRepository.countByStatus(InputControlStatus.COMPLETED);
    }

    /**
     * Реестр возвратов поставщику: группы брака, закрытые возвратом.
     *
     * Реестр собирают по группам, а не по карточкам: партию бракуют
     * частями, и поставщику уходит только та часть, по которой принято
     * решение {@code RETURN_TO_SUPPLIER}. Номера и причины подтягивают
     * двумя пакетными запросами на весь список — иначе сборка реестра
     * обращалась бы к базе по разу на каждую возвратную группу.
     */
    @Transactional(readOnly = true)
    public List<DefectRecordView> findReturns() {
        return defectRecords(groupRepository.findReturnGroupsDetailed());
    }

    /** Партний, отправленных на возврат: счётчик в боковом меню. */
    @Transactional(readOnly = true)
    public long countReturns() {
        return groupRepository.countReturnCards();
    }

    /**
     * Реестр изолятора брака: группы брака, оставленные на
     * предприятии.
     *
     * Устроен так же, как возврат, и собирает те же данные: у группы
     * одинаковая структура независимо от исхода, поэтому строке
     * изолятора нужны те же реквизиты партии, номера и цепочка причин.
     * Смысл перечня другой — это не основание рекламации, а список
     * ячеек, которые остались на предприятии и в производство не
     * пойдут, поэтому его читают по группам, а не по партиям: у одной
     * партии дефекты разные, и разбирать их построчно нужно именно
     * здесь.
     */
    @Transactional(readOnly = true)
    public List<DefectRecordView> findQuarantine() {
        return defectRecords(groupRepository.findQuarantineGroupsDetailed());
    }

    /** Партний с ячейками в изоляторе брака: счётчик в боковом меню. */
    @Transactional(readOnly = true)
    public long countQuarantine() {
        return groupRepository.countQuarantineCards();
    }

    /**
     * Изолятор брака с отбором по фильтру службы качества.
     *
     * Разновидность, классификатор и причина отсекают группы в запросе,
     * а не в памяти: руководителю нужен перечень конкретных ячеек, и
     * отобранные до сборки строки не тянут номера и причины лишних
     * групп. Причина берётся узлом дерева — выбранная причина верхнего
     * уровня захватывает и её уточнения.
     *
     * Разновидность уходит в запрос именем, а не перечислением: для
     * enum-параметра в предикате {@code is null} Hibernate 6 не строит
     * отображение значения
     * ({@code Could not determine ValueMapping for SqmParameter}), а
     * условие «фильтр не выбран» строит именно такой предикат. Строка
     * сравнивается с тем же написанием, что лежит в колонке по
     * {@code EnumType.STRING}, поэтому отсечение в базе не теряется.
     *
     * Пустой фильтр равносилен {@link #findQuarantine()}: перечень
     * открывают без отбора, условия добавляют по мере надобности.
     */
    @Transactional(readOnly = true)
    public List<DefectRecordView> findQuarantine(QuarantineFilter filter) {
        QuarantineFilter applied =
                filter == null ? QuarantineFilter.empty() : filter;

        QuarantineCellKind kind = applied.kind();

        return defectRecords(
                groupRepository.findQuarantineGroupsFiltered(
                        kind == null ? null : kind.name(),
                        applied.classifierId(),
                        applied.reasonId()
                )
        );
    }

    /** Классификаторы, чьи ячейки есть в изоляторе: опции фильтра. */
    @Transactional(readOnly = true)
    public List<InputControlDefectGroupRepository.QuarantineClassifierOption>
    findQuarantineClassifierOptions() {
        return groupRepository.findQuarantineClassifierOptions();
    }

    /**
     * Причины брака всего дерева: опции фильтра по типу брака.
     *
     * Отдают типы вместе с уточнениями, а не только первый уровень:
     * руководителю службы качества разбирать изолятор по конкретному
     * виду дефекта («Скол корпуса»), а не только по типу («Механическое
     * повреждение»), поэтому перечень фильтра строят по всему дереву.
     * Родителя подтягивают {@code fetch} — {@code open-in-view}
     * выключен, а шаблон группирует уточнения по ступени выше.
     */
    @Transactional(readOnly = true)
    public List<InputControlDefectReason> findQuarantineReasonOptions() {
        return defectReasonRepository.findAllActiveWithParent();
    }

    /**
     * Типы брака вместе с их видами: опции фильтра изолятора.
     *
     * Группу собирает {@link QuarantineReasonGroup}: изолятор читают и
     * бюро входного контроля, и служба качества, а дерево причин одно на
     * обоих — два набора подписей одного справочника читались бы как два
     * разных отбора.
     */
    @Transactional(readOnly = true)
    public List<QuarantineReasonGroup> findQuarantineReasonGroups() {
        return QuarantineReasonGroup.of(findQuarantineReasonOptions());
    }

    /**
     * Изолятор брака поштучно: одна строка — один номер ячейки.
     *
     * Реестр групп для разбора брака неудобен: руководителю бюро нужен
     * перечень конкретных ячеек, чтобы найти среди них свою. Поэтому
     * группу разворачивают в номера — у группы с отметкой «вся партия»
     * состав равен диапазону партии, у остальных внесёнными номерами.
     *
     * Развёртку делают в сервисе, а не в шаблоне: {@code open-in-view}
     * выключен, и обращение к номерам или причинам группы при отрисовке
     * упёрлось бы в ленивую связь.
     */
    @Transactional(readOnly = true)
    public List<QuarantineCellView> findQuarantineCells(QuarantineFilter filter) {
        List<QuarantineCellView> rows = new ArrayList<>();

        for (DefectRecordView record : findQuarantine(filter)) {
            for (String number : cellNumbers(record)) {
                rows.add(new QuarantineCellView(record, number));
            }
        }

        return rows;
    }

    /**
     * Полный перечень номеров группы.
     *
     * Группа «вся партия» не хранит номера поштучно — её состав равен
     * диапазону партии, и перечень обязан показать все ячейки, иначе
     * отыскивать в изоляторе было бы нечего.
     */
    private static List<String> cellNumbers(DefectRecordView record) {
        if (record.group().isDefectAll()) {
            CellReceivingBatch batch = record.batch();

            return batch == null ? List.of() : batch.fullNumbers();
        }

        return record.numbers();
    }

    /**
     * Строки реестра брака по выбранным группам.
     *
     * Сборку выносят в один метод, потому что возврат и изолятор
     * различаются только отбором групп в репозитории: номера и причины
     * подтягивают двумя пакетными запросами на весь список, и
     * повторяющий этот код второй реестр обращался бы к базе дважды
     * на тех же данных.
     */
    private List<DefectRecordView> defectRecords(
            List<InputControlDefectGroup> groups) {

        if (groups.isEmpty()) {
            return List.of();
        }

        List<Long> groupIds = groups.stream()
                .map(InputControlDefectGroup::getId)
                .toList();

        Map<Long, List<String>> numbersByGroup =
                cellRepository.findDefectsWithGroupByIds(groupIds).stream()
                        .collect(Collectors.groupingBy(
                                cell -> cell.getGroup().getId(),
                                LinkedHashMap::new,
                                Collectors.mapping(
                                        InputControlCardCell::getFullNumber,
                                        Collectors.toList()
                                )
                        ));

        Map<Long, List<InputControlCardReason>> reasonsByGroup =
                reasonRepository.findChainByGroupIds(groupIds).stream()
                        .collect(Collectors.groupingBy(
                                chosen -> chosen.getGroup().getId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        return groups.stream()
                .map(group -> new DefectRecordView(
                        group.getCard(),
                        group.getCard().getBatch(),
                        group,
                        numbersByGroup.getOrDefault(group.getId(), List.of()),
                        reasonsByGroup.getOrDefault(group.getId(), List.of())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public InputControlCard getCard(Long id) {
        return cardRepository.findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Карточка входного контроля не найдена"
                ));
    }

    /** Открытая карточка партии — по ней партию «берут в работу» повторно. */
    @Transactional(readOnly = true)
    public InputControlCard getCardByBatch(Long batchId) {
        return cardRepository.findOpenByBatchId(batchId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Карточка входного контроля не найдена"
                ));
    }

    /**
     * Открывает карточку партии.
     *
     * Повторный вызов возвращает уже открытую карточку: «взять в
     * работу» дважды нажато быть не может, а две открытые карточки
     * одной партии означали бы два разных результата одной проверки.
     */
    @Transactional
    public InputControlCard takeInWork(Long batchId, AppUser executor) {

        CellReceivingBatch batch = batchRepository.findDetailedById(batchId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Партия оприходования не найдена"
                ));

        if (batch.isPending()) {
            throw new IllegalStateException(
                    "Партия ещё не оприходована складом — "
                            + "входной контроль оформляют после прихода"
            );
        }

        return cardRepository.findOpenByBatchId(batchId)
                .orElseGet(() -> openCard(batch, executor));
    }

    /**
     * Данные для карточки: партия, её номера, справочник причин и
     * уже внесённые списки.
     *
     * Номера партии передаются отдельно: они нужны и как подсказка
     * при вводе, и для проверки «свой/чужой» на стороне шаблона
     * (подсветка строк), а считать их из диапазона дешевле, чем
     * держать в таблице.
     */
    @Transactional(readOnly = true)
    public InputControlCardView loadCardView(Long cardId) {

        InputControlCard card = getCard(cardId);

        List<String> batchNumbers = card.getBatch().fullNumbers();

        Map<Long, List<String>> numbersByGroup =
                groupNumbersByDefectGroup(cardId);

        Map<Long, List<InputControlCardReason>> reasonsByGroup =
                groupReasonsByDefectGroup(cardId);

        List<DefectGroupView> groups = groupRepository
                .findByCardIdOrdered(cardId).stream()
                .map(group -> new DefectGroupView(
                        group,
                        numbersByGroup.getOrDefault(group.getId(), List.of()),
                        reasonsByGroup.getOrDefault(group.getId(), List.of())
                ))
                .toList();

        return new InputControlCardView(
                card,
                batchNumbers,
                cellRepository.findByCardIdAndType(
                        cardId,
                        InputControlCellType.SAMPLE
                ).stream().map(InputControlCardCell::getFullNumber).toList(),
                groups,
                serialRepository.findControlDetailsByReceivingBatchId(
                        card.getBatch().getId()
                )
        );
    }

    /**
     * Забракованные номера, разложенные по группам.
     *
     * Порядок групп задаёт запрос (sort_order), поэтому перечень на
     * экране совпадает с порядком, в котором контролёр вводил дефекты.
     */
    private Map<Long, List<String>> groupNumbersByDefectGroup(Long cardId) {

        Map<Long, List<String>> numbers = new LinkedHashMap<>();

        cellRepository.findDefectsWithGroupByCardId(cardId)
                .forEach(cell -> numbers
                        .computeIfAbsent(
                                cell.getGroup().getId(),
                                key -> new ArrayList<>()
                        )
                        .add(cell.getFullNumber()));

        return numbers;
    }

    /** Цепочки причин, разложенные по группам. */
    private Map<Long, List<InputControlCardReason>> groupReasonsByDefectGroup(
            Long cardId) {

        Map<Long, List<InputControlCardReason>> reasons = new LinkedHashMap<>();

        reasonRepository.findDetailsByCardId(cardId)
                .forEach(chosen -> reasons
                        .computeIfAbsent(
                                chosen.getGroup().getId(),
                                key -> new ArrayList<>()
                        )
                        .add(chosen));

        return reasons;
    }

    /** Следующая ступень дерева причин для выбранной. */
    @Transactional(readOnly = true)
    public List<InputControlDefectReason> findChildren(Long parentId) {
        return parentId == null
                ? List.of()
                : defectReasonRepository.findChildren(parentId);
    }

    /**
     * Справочник причин целиком.
     *
     * Перечень небольшой и открыт по глубине, поэтому его отдают
     * целиком: страница раскрывает ступени по выбору на стороне
     * клиента, и дереву не нужен запрос на каждую причину.
     */
    @Transactional(readOnly = true)
    public List<InputControlDefectReason> findAllReasons() {
        return defectReasonRepository.findAllActiveWithParent();
    }

    /**
     * Сохраняет и закрывает карточку.
     *
     * Проверки собраны в одном месте, потому что их нарушают вместе:
     * чужой номер партии, брак без причины или одна ячейка в двух
     * группах — всё это делает карточку непригодной для отчёта,
     * поэтому закрывать её нельзя целиком, а не «частично принять».
     */
    @Transactional
    public InputControlResult saveAndComplete(Long cardId, InputControlForm form) {

        InputControlCard card = getCard(cardId);

        if (!card.isInWork()) {
            return InputControlResult.rejected(
                    "Проверка уже завершена — карточка закрыта"
            );
        }

        CellReceivingBatch batch = card.getBatch();
        Set<String> batchNumbers = new LinkedHashSet<>(batch.fullNumbers());

        List<String> sampleNumbers = parseNumbers(
                form.isSampleAll()
                        ? String.join("\n", batchNumbers)
                        : form.getSampleCellsText()
        );

        List<ParsedGroup> groups = parseGroups(form);

        InputControlType controlType =
                parseControlType(form.getInputControlType());

        if (controlType == null) {
            return InputControlResult.rejected(
                    "Не выбран вид входного контроля: ячейки партии "
                            + "отмечают либо визуальным контролем, либо "
                            + "визуальным и техническим"
            );
        }

        String problem = validate(
                batch,
                batchNumbers,
                sampleNumbers,
                form.isSampleAll(),
                groups
        );

        if (problem != null) {
            return InputControlResult.rejected(problem);
        }

        rewriteSampleCells(card, sampleNumbers);
        rewriteDefectGroups(card, groups);

        card.setSampleAll(form.isSampleAll());
        card.setInputControlType(controlType);
        card.setNote(trimToNull(form.getNote()));
        card.setStatus(InputControlStatus.COMPLETED);
        card.setCompletedAt(LocalDateTime.now());

        cardRepository.save(card);

        /*
         * Ячейки, не попавшие в брак, получают отметку контроля.
         *
         * Делают это после закрытия карточки: группы брака к этому
         * моменту уже записаны, и перечень забракованных номеров
         * берут из сохранённых данных — иначе «вся партия» в браке
         * осталась бы неучтённой, а её ячейки были бы ошибочно
         * помечены прошедшими контроль.
         */
        int marked = markSerials(
                card,
                controlType,
                card.getCheckedBy(),
                card.getCompletedAt()
        );

        return InputControlResult.accepted(
                groups.isEmpty()
                        ? "Проверка завершена: брак не выявлен, "
                        + marked + " яч. «" + controlType.getDisplayName()
                        + "» — партия передана в производство"
                        : "Проверка завершена: " + describeDefect(groups)
                        + (marked > 0
                        ? "; пройдено контролем " + marked + " яч."
                        : "")
        );
    }

    /**
     * Сохраняет заполненную карточку и отмечает незабракованные
     * ячейки партии контролем, не закрывая проверку.
     *
     * Кнопки «провести контроль» обязаны сохранить и уже введённую
     * выборку, и группы брака: их нажимают посреди заполнения, и
     * потеря уже введённой проверки заставила бы контролёра вносить
     * номера заново. Поэтому метод повторяет сохранение целиком, но
     * status карточки не трогает — итог по партии подводят отдельной
     * кнопкой завершения.
     *
     * Брак при отметке не трогают: ячейка в изоляторе или в возврате
     * контролем не помечается, иначе по отчёту она прошла бы приёмку
     * и вернулась в производство.
     */
    @Transactional
    public InputControlResult saveAndMark(Long cardId,
                                          InputControlForm form,
                                          InputControlType type,
                                          AppUser executor) {

        InputControlCard card = getCard(cardId);

        if (!card.isInWork()) {
            return InputControlResult.rejected(
                    "Проверка уже завершена — карточка закрыта"
            );
        }

        CellReceivingBatch batch = card.getBatch();
        Set<String> batchNumbers = new LinkedHashSet<>(batch.fullNumbers());

        List<String> sampleNumbers = parseNumbers(
                form.isSampleAll()
                        ? String.join("\n", batchNumbers)
                        : form.getSampleCellsText()
        );

        List<ParsedGroup> groups = parseGroups(form);

        String problem = validate(
                batch,
                batchNumbers,
                sampleNumbers,
                form.isSampleAll(),
                groups
        );

        if (problem != null) {
            return InputControlResult.rejected(problem);
        }

        rewriteSampleCells(card, sampleNumbers);
        rewriteDefectGroups(card, groups);

        card.setSampleAll(form.isSampleAll());
        card.setInputControlType(type);
        card.setNote(trimToNull(form.getNote()));
        cardRepository.save(card);

        int marked = markSerials(card, type, executor, LocalDateTime.now());

        return InputControlResult.accepted(
                "Контроль проведён: «" + type.getDisplayName() + "» — "
                        + marked + " яч. отмечено; карточка остаётся "
                        + "открытой, партия — на проверке"
        );
    }

    /**
     * Отмечает ячейки партии прошедшими контроль.
     *
     * Отмечают реестр ({@code product_serials}), а не карточку:
     * ячейка идёт по пути производства своей строкой, и отметка
     * обязана остаться на ней, а не в перечне одной проверки.
     *
     * Статус реестра при этом не меняется: {@code status} принадлежит
     * производству и закреплён ограничением {@code CHECK}, у колонки
     * не может быть двух хозяев. Отметка стоит в своих колонках.
     *
     * @return число отмеченных ячеек
     */
    private int markSerials(InputControlCard card,
                            InputControlType type,
                            AppUser executor,
                            LocalDateTime moment) {

        CellReceivingBatch batch = card.getBatch();

        List<InputControlDefectGroup> groups =
                groupRepository.findByCardIdOrdered(card.getId());

        /*
         * «Вся партия» в браке: незабракованных ячеек нет, и отмечать
         * нечего — проверять нечем.
         */
        if (groups.stream().anyMatch(InputControlDefectGroup::isDefectAll)) {
            return 0;
        }

        Set<String> defectNumbers =
                cellRepository.findDefectsWithGroupByCardId(card.getId())
                        .stream()
                        .map(InputControlCardCell::getFullNumber)
                        .collect(Collectors.toSet());

        Set<String> passedNumbers = batch.fullNumbers().stream()
                .filter(number -> !defectNumbers.contains(number))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (passedNumbers.isEmpty()) {
            return 0;
        }

        /*
         * Карточка хранит номера строками, а отметка ставится на
         * строку реестра: сверка идёт по полному номеру ячейки.
         */
        List<Long> ids = serialRepository
                .findControlDetailsByReceivingBatchId(batch.getId())
                .stream()
                .filter(serial -> passedNumbers.contains(serial.getFullNumber()))
                .map(ProductSerial::getId)
                .toList();

        if (ids.isEmpty()) {
            return 0;
        }

        serialRepository.markInputControlByIds(ids, type, moment, executor);

        return ids.size();
    }

    /** Вид контроля из формы; {@code null} — выбран неверный или пустой. */
    private static InputControlType parseControlType(String value) {
        if (isBlank(value)) {
            return null;
        }

        try {
            return InputControlType.valueOf(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Открытие карточки партии. */
    private InputControlCard openCard(CellReceivingBatch batch, AppUser executor) {

        InputControlCard card = new InputControlCard();

        card.setBatch(batch);
        card.setStatus(InputControlStatus.IN_WORK);
        card.setCheckedBy(executor);

        return cardRepository.save(card);
    }

    /**
     * Разбор групп брака из формы.
     *
     * Пустые блоки отбрасываются: кнопку «добавить группу» нажимают,
     * чтобы посмотреть форму, и незаполненная группа не должна
     * требовать ни номеров, ни решения.
     */
    private List<ParsedGroup> parseGroups(InputControlForm form) {

        List<ParsedGroup> parsed = new ArrayList<>();

        if (form.getDefectGroups() == null) {
            return parsed;
        }

        for (InputControlForm.DefectGroupForm group : form.getDefectGroups()) {

            if (isEmpty(group)) {
                continue;
            }

            parsed.add(new ParsedGroup(
                    parseNumbers(
                            group.isDefectAll()
                                    ? null
                                    : group.getDefectCellsText()
                    ),
                    resolveReasonChain(group.getReasonIds()),
                    customStepOf(group.getReasonIds()),
                    trimToNull(group.getCustomReasonText()),
                    group.isDefectAll(),
                    trimToNull(group.getDecision()),
                    /* Номер группы для сообщений — по порядку на экране. */
                    parsed.size() + 1
            ));
        }

        return parsed;
    }

    /**
     * Группа считается незаполненной, если в ней нет ни номеров, ни
     * отметки «вся партия», ни причины, ни решения.
     */
    private static boolean isEmpty(InputControlForm.DefectGroupForm group) {
        return group == null
                || (isBlank(group.getDefectCellsText())
                && !group.isDefectAll()
                && (group.getReasonIds() == null || group.getReasonIds().isEmpty())
                && isBlank(group.getCustomReasonText())
                && isBlank(group.getDecision()));
    }

    /** Ступень «Другое» в выбранной цепочке причин, если она есть. */
    private InputControlDefectReason customStepOf(List<Long> ids) {

        if (ids == null || ids.isEmpty()) {
            return null;
        }

        return ids.stream()
                .filter(Objects::nonNull)
                .map(id -> defectReasonRepository
                        .findByIdAndActiveTrue(id)
                        .orElse(null))
                .filter(Objects::nonNull)
                .filter(InputControlDefectReason::isCustom)
                .findFirst()
                .orElse(null);
    }

    /**
     * Проверка карточки.
     *
     * Возвращает текст проблемы либо {@code null}, если всё в порядке.
     * Порядок проверок — порядок заполнения формы: сначала выборочный
     * контроль, затем каждая группа брака по очереди, чтобы оператор
     * видел первую ошибку сверху, а не последнюю.
     */
    private String validate(CellReceivingBatch batch,
                            Set<String> batchNumbers,
                            List<String> sampleNumbers,
                            boolean sampleAll,
                            List<ParsedGroup> groups) {

        if (sampleNumbers.isEmpty()) {
            return "Внесите номера ячеек выборочного контроля "
                    + "или отметьте «все ячейки»";
        }

        List<String> foreignSample = sampleNumbers.stream()
                .filter(number -> !batchNumbers.contains(number))
                .toList();

        if (!foreignSample.isEmpty()) {
            return "В выборочном контроле номера не из этой партии: "
                    + join(foreignSample)
                    + ". Партия «" + batch.getCellLabel() + "» — "
                    + batch.getNumberRangeLabel();
        }

        if (!sampleAll && sampleNumbers.size() > batch.getQuantity()) {
            return "Номеров выборочного контроля больше, чем ячеек в партии ("
                    + batch.getQuantity() + " шт.)";
        }

        return validateGroups(batchNumbers, sampleNumbers, groups);
    }

    /**
     * Проверка групп брака.
     *
     * Каждая группа обязана быть самодостаточной: свои номера из
     * партии и из выборки, своя причина, своё решение. Между группами
     * проверяют только то, что нельзя вывести из отдельной группы, —
     * пересечение номеров и отметку «вся партия».
     */
    private String validateGroups(Set<String> batchNumbers,
                                  List<String> sampleNumbers,
                                  List<ParsedGroup> groups) {

        if (groups.isEmpty()) {
            return null;
        }

        Set<String> assigned = new HashSet<>();

        for (ParsedGroup group : groups) {

            String problem = validateGroup(group);

            if (problem != null) {
                return problem;
            }

            if (group.defectAll()) {

                /*
                 * «Вся партия» забирает все номера, поэтому делить их
                 * со второй группой нечем: вторая группа при такой
                 * отметке означает либо ошибку, либо намерение вернуть
                 * часть партии, уже отправленной целиком.
                 */
                if (groups.size() > 1) {
                    return "Группа " + group.position() + ": отметка "
                            + "«забраковать всю партию» не сочетается "
                            + "с другими группами — укажите номера явно";
                }

                continue;
            }

            if (group.numbers().isEmpty()) {
                return "Группа " + group.position() + ": внесите номера "
                        + "забракованных ячеек или отметьте «всю партию»";
            }

            List<String> foreign = group.numbers().stream()
                    .filter(number -> !batchNumbers.contains(number))
                    .toList();

            if (!foreign.isEmpty()) {
                return "Группа " + group.position()
                        + ": номера не из этой партии — " + join(foreign);
            }

            /*
             * Ячейка физически не может лежать в двух изоляторах:
             * две группы с одним номером означают два разных решения
             * об одной ячейке.
             */
            List<String> duplicated = group.numbers().stream()
                    .filter(assigned::contains)
                    .toList();

            if (!duplicated.isEmpty()) {
                return "Один номер не может быть в двух группах брака: "
                        + join(duplicated);
            }

            assigned.addAll(group.numbers());

            /*
             * Бракуют только проверенное: выборочный контроль — то,
             * что контролёр действительно осмотрел.
             */
            List<String> unchecked = group.numbers().stream()
                    .filter(number -> !sampleNumbers.contains(number))
                    .toList();

            if (!unchecked.isEmpty()) {
                return "Группа " + group.position()
                        + ": забраковать можно только проверенную ячейку — "
                        + "в выборочном контроле нет " + join(unchecked);
            }
        }

        return null;
    }

    /** Проверка одной группы: причина, текст «Другого», решение. */
    private static String validateGroup(ParsedGroup group) {

        if (group.chain().isEmpty()) {
            return "Группа " + group.position() + ": укажите причину брака — "
                    + "без причины ячейки нельзя ни переместить в изолятор, "
                    + "ни вернуть поставщику";
        }

        if (group.customStep() != null && group.customText() == null) {
            return "Группа " + group.position()
                    + ": введите текст причины для пункта «Другое»";
        }

        if (group.decision() == null) {
            return "Группа " + group.position() + ": выберите решение — "
                    + "перемещение в изолятор брака или возврат поставщику";
        }

        try {
            InputControlDecision.valueOf(group.decision());
        } catch (IllegalArgumentException exception) {
            return "Группа " + group.position() + ": решение не распознано";
        }

        return null;
    }

    /**
     * Разбор поля с номерами.
     *
     * Поле заполняют и с клавиатуры, и сканером: строки разделяются
     * переводом, запятой или точкой с запятой, сканер добавляет
     * префиксы вроде «CELL:» и пробелы. Оставляют только цифры,
     * годные по длине номера; нераспознанные строки отсекаются здесь
     * и всплывают как «номера не из партии», потому что по длине они
     * не совпадают с полным номером ячейки.
     */
    private List<String> parseNumbers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> numbers = new LinkedHashSet<>();

        for (String line : text.split("[,;\\r\\n]+")) {

            String digits = line.replaceAll("\\D", "");

            if (digits.length() == FULL_NUMBER_LENGTH) {
                numbers.add(digits);
            }
        }

        return List.copyOf(numbers);
    }

    /**
     * Цепочка причин из формы.
     *
     * Идентификаторы приходят из запроса и могут быть переданы
     * вручную, поэтому каждый шаг проверяется на существование и на
     * связь со предыдущим: «следующая причина» обязана быть уточнением
     * выбранной, иначе в группе окажется набор не связанных между
     * собой причин.
     */
    private List<InputControlDefectReason> resolveReasonChain(List<Long> ids) {

        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        List<InputControlDefectReason> chain = new ArrayList<>(ids.size());
        InputControlDefectReason previous = null;

        for (Long id : ids) {

            if (id == null) {
                continue;
            }

            InputControlDefectReason reason = defectReasonRepository
                    .findByIdAndActiveTrue(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Причина брака не найдена или исключена из справочника"
                    ));

            if (previous != null) {

                InputControlDefectReason parent = previous;

                boolean follows = defectReasonRepository
                        .findChildren(parent.getId())
                        .stream()
                        .anyMatch(child -> child.getId().equals(reason.getId()));

                if (!follows) {
                    throw new IllegalArgumentException(
                            "Причина «" + reason.getName()
                                    + "» не является следующей для «"
                                    + parent.getName() + "»"
                    );
                }
            }

            if (!chain.stream().anyMatch(chosen -> chosen.getId().equals(reason.getId()))) {
                chain.add(reason);
            }

            previous = reason;
        }

        return chain;
    }

    /**
     * Заводит в справочник написание, введённое в ступени «Другое».
     *
     * Новая запись встаёт на ту же ступень, что и «Другое»: причина,
     * придуманная контролёром, — полноценный пункт перечня, а не
     * уточнение служебного пункта. Написание, которое уже есть в
     * справочнике, не размножается.
     */
    private InputControlDefectReason registerCustomReason(
            String text,
            InputControlDefectReason customStep) {

        Long levelParentId = customStep.getParent() == null
                ? null
                : customStep.getParent().getId();

        return defectReasonRepository
                .findByLevelAndName(levelParentId, text)
                .orElseGet(() -> {

                    InputControlDefectReason reason = new InputControlDefectReason();

                    reason.setName(text);
                    reason.setParent(customStep.getParent());
                    reason.setCustom(false);
                    reason.setActive(true);

                    return defectReasonRepository.save(reason);
                });
    }

    /**
     * Перезапись перечня выборочного контроля.
     *
     * Удаление и запись целиком дешевле сверки «что изменилось»:
     * карточку заполняют за один присест, а перечень на экране обязан
     * совпадать с перечнем в базе.
     */
    private void rewriteSampleCells(InputControlCard card,
                                    List<String> numbers) {

        cellRepository.deleteByCardIdAndType(
                card.getId(),
                InputControlCellType.SAMPLE
        );

        cellRepository.saveAll(numbers.stream()
                .map(number -> new InputControlCardCell(
                        card,
                        number,
                        InputControlCellType.SAMPLE
                ))
                .toList());
    }

    /**
     * Перезапись групп брака.
     *
     * Группы пересоздают целиком, а не обновляют по id: набор групп
     * контролёр меняет на экране произвольно (добавляет, удаляет), и
     * сопоставлять строки формы с уже сохранёнными группами дороже,
     * чем записать их заново в том порядке, что виден на экране.
     *
     * Порядок удаления задан зависимостями: причины и номера ссылаются
     * на группу, поэтому их снимают раньше групп.
     */
    private void rewriteDefectGroups(InputControlCard card,
                                     List<ParsedGroup> groups) {

        Long cardId = card.getId();

        reasonRepository.deleteByCardId(cardId);
        cellRepository.deleteByCardIdAndType(cardId, InputControlCellType.DEFECT);
        groupRepository.deleteByCardId(cardId);

        if (groups.isEmpty()) {
            return;
        }

        for (ParsedGroup parsed : groups) {

            InputControlDefectReason customReason = parsed.customStep() == null
                    ? null
                    : registerCustomReason(parsed.customText(), parsed.customStep());

            InputControlDefectGroup saved = groupRepository.save(
                    toEntity(card, parsed)
            );

            /*
             * «Вся партия» означает весь диапазон: писать каждую ячейку
             * отдельной строкой незачем, состав восстанавливают по
             * отметке группы.
             */
            if (!parsed.defectAll()) {
                cellRepository.saveAll(parsed.numbers().stream()
                        .map(number -> new InputControlCardCell(saved, number))
                        .toList());
            }

            reasonRepository.saveAll(toReasonRows(saved, parsed, customReason));
        }
    }

    private static InputControlDefectGroup toEntity(InputControlCard card,
                                                    ParsedGroup parsed) {

        InputControlDefectGroup group = new InputControlDefectGroup();

        group.setCard(card);
        group.setDecision(InputControlDecision.valueOf(parsed.decision()));
        group.setDefectAll(parsed.defectAll());
        group.setSortOrder(parsed.position());

        return group;
    }

    /**
     * Строки цепочки причин группы.
     *
     * Для ступени «Другое» пишется заведённая запись справочника, а её
     * исходная формулировка сохраняется отдельно: справочник живёт
     * написаниями, а проверка — тем, что написал контролёр.
     */
    private static List<InputControlCardReason> toReasonRows(
            InputControlDefectGroup group,
            ParsedGroup parsed,
            InputControlDefectReason customReason) {

        List<InputControlCardReason> rows =
                new ArrayList<>(parsed.chain().size());

        for (int index = 0; index < parsed.chain().size(); index++) {

            InputControlDefectReason step = parsed.chain().get(index);

            boolean custom = parsed.customStep() != null
                    && parsed.customStep().getId().equals(step.getId());

            InputControlCardReason row = new InputControlCardReason();

            row.setCard(group.getCard());
            row.setGroup(group);
            row.setReason(custom && customReason != null ? customReason : step);
            row.setLevelNumber(index + 1);
            row.setCustomText(custom ? parsed.customText() : null);

            rows.add(row);
        }

        return rows;
    }

    /** Итог по браку для сообщения: группы, их ячейки и решения. */
    private static String describeDefect(List<ParsedGroup> groups) {

        return groups.stream()
                .map(group -> "группа " + group.position() + " — "
                        + (group.defectAll()
                        ? "вся партия"
                        : group.numbers().size() + " яч.")
                        + ", решение: «" + decisionLabel(group.decision()) + "»")
                .collect(Collectors.joining("; "));
    }

    private static String decisionLabel(String decision) {
        try {
            return InputControlDecision.valueOf(decision).getDisplayName();
        } catch (IllegalArgumentException exception) {
            return decision;
        }
    }

    /**
     * Перечень номеров в сообщении: длинный список обрезают, иначе
     * плашка растянулась бы на весь экран.
     */
    private static String join(List<String> numbers) {
        return numbers.size() <= MESSAGE_NUMBER_LIMIT
                ? String.join(", ", numbers)
                : String.join(", ", numbers.subList(0, MESSAGE_NUMBER_LIMIT))
                + " …";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Карточка со всеми данными для экрана: партия, её номера,
     * внесённые списки, группы брака, справочник причин.
     *
     * {@code serials} — строки реестра этой партии с отметками
     * контроля: контролёр обязан видеть, какие ячейки контроль уже
     * прошли, иначе он отмечал бы их повторно вслепую.
     */
    public record InputControlCardView(
            InputControlCard card,
            List<String> batchNumbers,
            List<String> sampleNumbers,
            List<DefectGroupView> defectGroups,
            List<ProductSerial> serials
    ) {

        /** Отметки контроля партии, сопоставленные с полным номером. */
        public Map<String, ProductSerial> serialsByNumber() {
            return serials.stream().collect(Collectors.toMap(
                    ProductSerial::getFullNumber,
                    Function.identity(),
                    (first, second) -> first
            ));
        }
    }

    /**
     * Группа брака для экрана: её решение, номера и цепочка причин.
     *
     * Перечень собирает сервис, потому что коллекции сущностей
     * ленивые, а {@code open-in-view} выключен.
     */
    public record DefectGroupView(
            InputControlDefectGroup group,
            List<String> numbers,
            List<InputControlCardReason> reasons
    ) {

        /** Подпись состава группы для закрытой карточки. */
        public String getCompositionLabel() {
            return group.isDefectAll()
                    ? "забракована вся партия"
                    : numbers.isEmpty()
                    ? "номера не внесены"
                    : numbers.size() + " яч.: " + String.join(", ", numbers);
        }
    }

    /**
     * Строка реестра брака: группа с её партией и карточкой.
     *
     * Служит обоим реестрам — возврату поставщику и изолятору брака:
     * у группы одинаковый набор реквизитов независимо от исхода,
     * поэтому различать их двумя типами незачем.
     *
     * Партия и карточка переданы отдельно, а не читались бы из
     * ленивых связей: {@code open-in-view} выключен, а реестру нужны
     * реквизиты (поставщик, ордер, диапазон) прямо в строке таблицы.
     */
    public record DefectRecordView(
            InputControlCard card,
            CellReceivingBatch batch,
            InputControlDefectGroup group,
            List<String> numbers,
            List<InputControlCardReason> reasons
    ) {

        /**
         * Состав группы: «вся партия» подписывается диапазоном
         * партии — перечислять её номера построчно незачем, состав
         * восстановлен по отметке группы.
         */
        public String getCompositionLabel() {
            return group.isDefectAll()
                    ? "вся партия: " + batch.getNumberRangeLabel()
                    : numbers.isEmpty()
                    ? "номера не внесены"
                    : numbers.size() + " яч.: " + String.join(", ", numbers);
        }

        /** Основание решения: цепочка причин через «→». */
        public String getReasonChainLabel() {
            return reasons.isEmpty()
                    ? "—"
                    : reasons.stream()
                    .map(InputControlCardReason::getDisplayLabel)
                    .collect(Collectors.joining(" → "));
        }

        /**
         * Разновидность изделия строки: ячейка либо подсборка.
         *
         * Граница проходит по факту приёмки партии складом, а не по
         * типу классификатора, — поэтому разновидность выводят из
         * {@code batch.acceptedAt}, а не хранят в группе.
         */
        public QuarantineCellKind getKind() {
            return QuarantineCellKind.ofAccepted(
                    batch != null && batch.getAcceptedAt() != null
            );
        }

        /** Подпись разновидности для колонок перечня качества. */
        public String getKindLabel() {
            return getKind().getDisplayName();
        }
    }

    /**
     * Группа брака после разбора формы: готовые к проверке и записи
     * номера, цепочка причин и решение.
     *
     * @param position номер группы на экране — сообщения об ошибке
     *                 обязаны указывать, какой блок исправлять
     */
    private record ParsedGroup(
            List<String> numbers,
            List<InputControlDefectReason> chain,
            InputControlDefectReason customStep,
            String customText,
            boolean defectAll,
            String decision,
            int position
    ) {
    }
}