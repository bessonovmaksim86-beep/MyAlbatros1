package ru.company.production.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.company.production.dto.BvkAcceptanceForm;
import ru.company.production.dto.InputControlResult;
import ru.company.production.entity.AppUser;
import ru.company.production.entity.CellReceivingBatch;
import ru.company.production.entity.InputControlCard;
import ru.company.production.entity.InputControlCardCell;
import ru.company.production.entity.InputControlDefectGroup;
import ru.company.production.entity.ProductSerial;
import ru.company.production.repository.CellReceivingBatchRepository;
import ru.company.production.repository.InputControlCardCellRepository;
import ru.company.production.repository.InputControlCardRepository;
import ru.company.production.repository.InputControlDefectGroupRepository;
import ru.company.production.repository.ProductSerialRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Приёмка на склад полуфабрикатов партии, вернувшейся из БВК.
 *
 * Маршрут партии: склад оприходовал → БВК проверил и часть ячеек
 * отсеял на брак → прошедшие контроль ячейки возвращаются кладовщику,
 * и он оформляет приёмку. Итог — «Подсборка принята на склад».
 *
 * Ключевое отличие от оприходования: состав приёмки определяет не
 * склад, а результат проверки. Ячейку, забракованную в БВК, принять
 * нельзя физически — она лежит в изоляторе или уехала поставщику.
 * Поэтому перечень, который вводит кладовщик, сверяют с двумя
 * множествами: диапазоном партии и перечнем её брака.
 *
 * Брак берут из последней завершённой карточки партии: группа с
 * отметкой «вся партия» означает, что на склад не вернётся ни одна
 * ячейка, и приёмку такой партии проводят пустым перечнем — то есть
 * не проводят вовсе.
 *
 * Приёмку пишут отдельными отметками партии и ячейки, а не в
 * {@code product_serials.status}: статус принадлежит производству и
 * закреплён CHECK (тот же принцип, что у отметки входного контроля).
 */
@Service
@RequiredArgsConstructor
public class BvkAcceptanceService {

    /** Длина полного номера: 4 цифры кода + 5 цифры серии. */
    private static final int FULL_NUMBER_LENGTH = 9;

    /** Номеров в сообщении об ошибке: длинный список не читаем. */
    private static final int MESSAGE_NUMBER_LIMIT = 12;

    private final CellReceivingBatchRepository batchRepository;
    private final InputControlCardRepository cardRepository;
    private final InputControlCardCellRepository cardCellRepository;
    private final InputControlDefectGroupRepository groupRepository;
    private final ProductSerialRepository serialRepository;
    private final SerialMovementJournalService journal;
    /**
     * Партии, вернувшиеся от БВК, с числом забракованных ячеек.
     *
     * Брак считают пакетом на весь список: партия в журнале может быть
     * не одна, а кладовщику нужно видеть, сколько ячеек придёт фактически.
     */
    @Transactional(readOnly = true)
    public List<AcceptanceBatchView> findBatches() {

        List<CellReceivingBatch> batches = batchRepository.findBvkCheckedBatches();

        if (batches.isEmpty()) {
            return List.of();
        }

        Map<Long, Set<String>> defects = defectNumbersByBatch(
                completedCardsByBatch(idsOf(batches)),
                batches.stream()
                        .collect(Collectors.toMap(
                                CellReceivingBatch::getId,
                                CellReceivingBatch::fullNumbers
                        ))
        );

        return batches.stream()
                .map(batch -> new AcceptanceBatchView(
                        batch,
                        defects.getOrDefault(batch.getId(), Set.of()).size()
                ))
                .toList();
    }

    /** Партий, ожидающих приёмки: счётчик в боковом меню. */
    @Transactional(readOnly = true)
    public long countAwaiting() {
        return batchRepository.countAwaitingAcceptance();
    }

    /**
     * Данные экрана приёмки: партия, её прошедшие контроль номера,
     * перечень брака и строки реестра с отметками.
     */
    @Transactional(readOnly = true)
    public AcceptanceView loadView(Long batchId) {

        CellReceivingBatch batch = get(batchId);

        Map<Long, InputControlCard> cards =
                completedCardsByBatch(List.of(batchId));

        if (!cards.containsKey(batchId)) {
            throw new IllegalStateException(
                    "Партия «" + batch.getCellLabel() + "» ещё не проверена "
                            + "входным контролем — принять её на склад нельзя"
            );
        }

        Set<String> defectNumbers = defectNumbersByBatch(
                cards,
                Map.of(batchId, batch.fullNumbers())
        ).getOrDefault(batchId, Set.of());

        List<String> availableNumbers = batch.fullNumbers().stream()
                .filter(number -> !defectNumbers.contains(number))
                .toList();

        return new AcceptanceView(
                batch,
                cards.get(batchId),
                availableNumbers,
                new ArrayList<>(defectNumbers),
                serialRepository.findAcceptanceDetailsByReceivingBatchId(batchId)
        );
    }

    @Transactional(readOnly = true)
    public CellReceivingBatch get(Long id) {
        return batchRepository.findDetailedById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Партия оприходования не найдена"
                ));
    }

    /**
     * Проводит приёмку: ячейки помечают принятыми на склад, партию —
     * принятой.
     *
     * Проверки собраны здесь, а не в форме: кладовщик обязан получить
     * человеческое объяснение («эти ячейки в браке»), а не служебный
     * отказ. При любой нестыковке не отмечает ни одной ячейки —
     * частичная приёмка оставила бы реестр расходящимся с тем, что
     * реально лежит на складе.
     *
     * Отметку «все номера» трактуют как полный перечень прошедших
     * контроль ячеек: брак в него не входит по построению, поэтому
     * подтверждение кладовщика относится именно к этому составу.
     */
    @Transactional
    public InputControlResult accept(Long batchId,
                                     BvkAcceptanceForm form,
                                     AppUser receiver) {

        CellReceivingBatch batch = get(batchId);

        if (batch.isAccepted()) {
            return InputControlResult.rejected(
                    "Партия «" + batch.getCellLabel() + "» уже принята на склад "
                            + batch.getFormattedAcceptedAt()
                            + " — повторное проведение не требуется"
            );
        }

        if (batch.isPending()) {
            return InputControlResult.rejected(
                    "Партия ещё не оприходована складом — приёмку после БВК "
                            + "проводят по закрытому приходу"
            );
        }

        Map<Long, InputControlCard> cards =
                completedCardsByBatch(List.of(batchId));

        if (!cards.containsKey(batchId)) {
            return InputControlResult.rejected(
                    "Партия «" + batch.getCellLabel() + "» ещё не проверена "
                            + "входным контролем — принять её на склад нельзя"
            );
        }

        Set<String> defectNumbers = defectNumbersByBatch(
                cards,
                Map.of(batchId, batch.fullNumbers())
        ).getOrDefault(batchId, Set.of());

        Set<String> batchNumbers = new LinkedHashSet<>(batch.fullNumbers());

        List<String> availableNumbers = batchNumbers.stream()
                .filter(number -> !defectNumbers.contains(number))
                .toList();

        if (availableNumbers.isEmpty()) {
            return InputControlResult.rejected(
                    "Вся партия «" + batch.getCellLabel() + "» отсеяна на брак "
                            + "во входном контроле — принимать на склад нечего"
            );
        }

        List<String> chosen = form.isAllCells()
                ? availableNumbers
                : parseNumbers(form.getCellNumbersText());

        if (chosen.isEmpty()) {
            return InputControlResult.rejected(
                    "Внесите номера принимаемых ячеек или отметьте "
                            + "«отметить все номера ячеек»"
            );
        }

        String problem = validate(batch, batchNumbers, availableNumbers, chosen);

        if (problem != null) {
            return InputControlResult.rejected(problem);
        }

        LocalDateTime moment = LocalDateTime.now();

        /*
         * Карточка и форма оперируют номерами, отметка стоит на строке
         * реестра: сверка идёт по полному номеру ячейки.
         */
        List<ProductSerial> acceptedSerials = serialRepository
                .findAcceptanceDetailsByReceivingBatchId(batchId).stream()
                .filter(serial -> chosen.contains(serial.getFullNumber()))
                .toList();

        if (acceptedSerials.isEmpty()) {
            return InputControlResult.rejected(
                    "Ни одна из внесённых ячеек не найдена в реестре партии — "
                            + "проверьте номера"
            );
        }

        List<Long> ids = acceptedSerials.stream()
                .map(ProductSerial::getId)
                .toList();

        if (ids.isEmpty()) {
            return InputControlResult.rejected(
                    "Ни одна из внесённых ячеек не найдена в реестре партии — "
                            + "проверьте номера"
            );
        }

        /*
         * Принимаем: accepted_at = «лежит на складе», cell_ready_at =
         * «готовая ячейка». Без галочки ставим только accepted_at —
         * позиция остаётся подсборкой. С галочкой — ставим обе отметки,
         * позиция сразу выходит в комплектование и отчёты.
         */
        LocalDateTime cellReadyMoment = form.isCellReady() ? moment : null;

        serialRepository.markAcceptedByIds(ids, moment, receiver);

        if (cellReadyMoment != null) {
            serialRepository.markCellReadyByIds(ids, cellReadyMoment, receiver);
        }

        batch.setAcceptedAt(moment);
        batch.setAcceptedBy(receiver);
        batch.setAcceptedQuantity(ids.size());

        if (form.isCellReady()) {
            batch.setCellReadyAt(cellReadyMoment);
            batch.setCellReadyBy(receiver);
        }

        batchRepository.save(batch);

        journal.recordAccepted(acceptedSerials, receiver, moment);

        if (cellReadyMoment != null) {
            journal.recordCellReady(acceptedSerials, receiver, cellReadyMoment);
        }
        int defects = (int) batchNumbers.stream()
                .filter(defectNumbers::contains)
                .count();

        return InputControlResult.accepted(
                "Подсборка принята на склад: " + ids.size() + " яч."
                        + (defects > 0 ? "; не принято на брак " + defects + " яч." : "")
        );
    }

    /**
     * Сверка перечня приёмки.
     *
     * Брак проверяют раньше прочего: он означает не опечатку, а
     * намерение принять то, что на склад не возвращают, и оператор
     * обязан увидеть именно это, а не «номер не из партии».
     */
    private static String validate(CellReceivingBatch batch,
                                   Set<String> batchNumbers,
                                   List<String> availableNumbers,
                                   List<String> chosen) {

        List<String> defective = chosen.stream()
                .filter(number -> !availableNumbers.contains(number))
                .filter(batchNumbers::contains)
                .toList();

        if (!defective.isEmpty()) {
            return "Эти ячейки отсеяны на брак во входном контроле и на склад "
                    + "не возвращаются: " + join(defective);
        }

        List<String> foreign = chosen.stream()
                .filter(number -> !batchNumbers.contains(number))
                .toList();

        if (!foreign.isEmpty()) {
            return "Номера не из этой партии: " + join(foreign)
                    + ". Партия «" + batch.getCellLabel() + "» — "
                    + batch.getNumberRangeLabel();
        }

        return null;
    }

    /**
     * Завершённые карточки партий: по партии берут самую свежую —
     * запрос уже отсортирован, поэтому достаточно первого вхождения.
     */
    private Map<Long, InputControlCard> completedCardsByBatch(List<Long> batchIds) {

        Map<Long, InputControlCard> cardsByBatch = new java.util.LinkedHashMap<>();

        cardRepository.findCompletedByBatchIds(batchIds)
                .forEach(card -> cardsByBatch.putIfAbsent(
                        card.getBatch().getId(),
                        card
                ));

        return cardsByBatch;
    }

    /**
     * Забракованные номера по партиям.
     *
     * Собирают из двух источников: строки {@code DEFECT} и отметка
     * группы «вся партия» — вторая отдельные номера не заводит, а на
     * склад при ней не вернётся ничего. Номера партии приходят
     * параметром: они уже посчитаны по диапазону, и читать их через
     * ленивую связь классификатора незачем.
     */
    private Map<Long, Set<String>> defectNumbersByBatch(
            Map<Long, InputControlCard> cardsByBatch,
            Map<Long, List<String>> numbersByBatch) {

        if (cardsByBatch.isEmpty()) {
            return Map.of();
        }

        List<Long> cardIds = cardsByBatch.values().stream()
                .map(InputControlCard::getId)
                .toList();

        Map<Long, Set<String>> defectsByCard =
                cardCellRepository.findDefectsByCardIds(cardIds).stream()
                        .collect(Collectors.groupingBy(
                                cell -> cell.getCard().getId(),
                                Collectors.mapping(
                                        InputControlCardCell::getFullNumber,
                                        Collectors.toCollection(LinkedHashSet::new)
                                )
                        ));

        Map<Long, Long> batchIdByCardId = cardsByBatch.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getValue().getId(),
                        Map.Entry::getKey
                ));

        groupRepository.findByCardIds(cardIds).stream()
                .filter(InputControlDefectGroup::isDefectAll)
                .forEach(group -> {
                    Long batchId = batchIdByCardId.get(group.getCard().getId());
                    List<String> wholeBatch =
                            numbersByBatch.getOrDefault(batchId, List.of());

                    defectsByCard
                            .computeIfAbsent(
                                    group.getCard().getId(),
                                    key -> new LinkedHashSet<>()
                            )
                            .addAll(wholeBatch);
                });

        return cardsByBatch.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> defectsByCard.getOrDefault(
                                entry.getValue().getId(),
                                Set.of()
                        )
                ));
    }

    /**
     * Разбор поля с номерами.
     *
     * Поле заполняют и с клавиатуры, и сканером: строки разделяются
     * переводом, запятой или точкой с запятой, сканер добавляет
     * префиксы вроде «CELL:» и пробелы. Оставляют только цифры, годные
     * по длине полного номера; остальное отсекается здесь и всплывает
     * как «номера не из партии».
     */
    private static List<String> parseNumbers(String text) {

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
     * Перечень номеров в сообщении: длинный список обрезают, иначе
     * плашка растянулась бы на весь экран.
     */
    private static String join(List<String> numbers) {
        return numbers.size() <= MESSAGE_NUMBER_LIMIT
                ? String.join(", ", numbers)
                : String.join(", ", numbers.subList(0, MESSAGE_NUMBER_LIMIT))
                + " …";
    }

    private static List<Long> idsOf(List<CellReceivingBatch> batches) {
        return batches.stream().map(CellReceivingBatch::getId).toList();
    }

    /**
     * Строка списка партий, ожидающих приёмки: приход плюс число ячеек,
     * отсеянных на брак.
     */
    public record AcceptanceBatchView(
            CellReceivingBatch batch,
            int defectCount
    ) {

        /** Ячеек вернётся на склад: партия за вычетом брака. */
        public int getAvailableCount() {
            return Math.max(batch.getQuantity() - defectCount, 0);
        }
    }

    /**
     * Экран приёмки: партия, её карточка БВК, доступные и забракованные
     * номера, строки реестра.
     *
     * {@code serialsByNumber} нужна шаблону, чтобы подсветить ячейки,
     * приёмку которых уже провели, — коллекции сущностей ленивые, а
     * {@code open-in-view} выключен, поэтому список готовит сервис.
     */
    public record AcceptanceView(
            CellReceivingBatch batch,
            InputControlCard card,
            List<String> availableNumbers,
            List<String> defectNumbers,
            List<ProductSerial> serials
    ) {

        public Map<String, ProductSerial> serialsByNumber() {
            return serials.stream().collect(Collectors.toMap(
                    ProductSerial::getFullNumber,
                    Function.identity(),
                    (first, second) -> first
            ));
        }
    }
}
