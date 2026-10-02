package ru.company.production.service;

import ru.company.production.entity.CellReceivingBatch;

/**
 * Строка поштучного перечня изолятора брака: одна ячейка — одна строка.
 *
 * Реестр групп для разбора брака неудобен: руководителю бюро нужен
 * перечень конкретных ячеек, чтобы найти среди них свою. Поэтому группу
 * разворачивают в номера, а строка держит ссылку на всю строку реестра
 * (партию, карточку, группу) и только переадресует обращения — сами
 * ленивые связи она не читает, {@code open-in-view} выключен.
 *
 * Тип отдельный, а не строка службы качества: {@code QualityService}
 * уже зависит от {@link InputControlService}, поэтому переиспользовать
 * его {@code QuarantineCellRow} отсюда значило бы замкнуть цикл бинов.
 * Набор подписей при этом совпадает: перечень читают оба кабинета, и
 * одна и та же ячейка обязана выглядеть одинаково.
 */
public record QuarantineCellView(
        InputControlService.DefectRecordView record,
        String fullNumber
) {

    /** Поставщик партии, по которому ищут ячейку в изоляторе. */
    public String getSupplierName() {
        CellReceivingBatch batch = record.batch();

        return batch == null ? "—" : batch.getSupplierName();
    }

    /** Приходный ордер партии. */
    public String getIncomingOrderNumber() {
        CellReceivingBatch batch = record.batch();

        return batch == null ? "—" : batch.getIncomingOrderNumber();
    }

    /** Наименование изделия: «1212 — Подсборка ЯПР56». */
    public String getCellLabel() {
        CellReceivingBatch batch = record.batch();

        return batch == null ? fullNumber : batch.getCellLabel();
    }

    /** Ячейка это или подсборка. */
    public String getKindLabel() {
        return record.getKindLabel();
    }

    /** Причина брака: цепочка типов и видов через «→». */
    public String getReasonChain() {
        return record.getReasonChainLabel();
    }

    /** Исход по группе: «Перемещение в изолятор брака» и т. п. */
    public String getDecisionLabel() {
        return record.group().getDecisionLabel();
    }

    /** Цвет плашки исхода — тот же, что в реестре брака. */
    public String getDecisionBadgeClass() {
        return record.group().getDecisionBadgeClass();
    }

    /** Дата завершения проверки. */
    public String getCompletedLabel() {
        return record.card().getFormattedCompletedAt();
    }

    /** Контролёр, оформивший проверку. */
    public String getCheckerName() {
        return record.card().getCheckerName();
    }

    public Long getCardId() {
        return record.card().getId();
    }

    public Long getGroupId() {
        return record.group().getId();
    }

    /** Идентификатор изделия: по нему сверяют отбор по классификатору. */
    public Long getClassifierId() {
        CellReceivingBatch batch = record.batch();

        if (batch == null || batch.getCellClassifier() == null) {
            return null;
        }

        return batch.getCellClassifier().getId();
    }

    /*
     * Двойники вида getX() держат рядом с аксессорами record
     * намеренно: Thymeleaf читает свойства по JavaBeans-соглашению
     * (get<Имя>() / is<Имя>()), а record компилируется с аксессором
     * <имя>() без префикса. Без этих методов строка перечня
     * отрисовалась бы пустой или страница упала бы на
     * PropertyOrFieldNotAvailableException.
     */
    public String getFullNumber() {
        return fullNumber;
    }
}
