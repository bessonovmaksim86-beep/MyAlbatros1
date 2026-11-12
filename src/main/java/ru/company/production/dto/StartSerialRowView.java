package ru.company.production.dto;

import ru.company.production.entity.ProductClassifier;
import ru.company.production.entity.ProductType;
import ru.company.production.service.SerialNumbering;

/**
 * Строка раздела «Стартовые номера серий».
 *
 * Показывает заданный старт, максимум, до которого ряд уже занят в
 * реестре, и номер, который система выдаст следующим. Разрыв между
 * вторым и первым — единственный способ проверить старт на месте: если
 * занятый максимум не ниже введённого, заданное значение уже не сработает.
 *
 * Область нумерации ({@code numberingLabel}) обязательна: у датчиков ряд
 * общий на весь род, и без подписи «род» администратор поставил бы разным
 * классификаторам одного рода разные старты.
 *
 * {@code effectiveStart} считает сервис, а не запись: у датчика
 * фактический старт ряда — наибольший из заданных у всех классификаторов
 * рода, поэтому одиночному классификатору его вычислить нечем.
 */
public record StartSerialRowView(

        Long id,

        Integer code,

        ProductType productType,

        String displayName,

        /** Ключ области нумерации: {@code S{род}} либо {@code C{классификатор}}. */
        String numberingKey,

        /** Подпись области: «род «ДУУ2М»» либо «классификатор». */
        String numberingLabel,

        /** Старт, заданный именно этому классификатору (null — не задан). */
        Integer startSerial,

        /** Старт, которым ряд реально начинается (минимум 1). */
        int effectiveStart,

        /** Самый большой занятый номер ряда (0, если ряда ещё нет). */
        int maxUsedSerial
) {

    public static StartSerialRowView of(ProductClassifier classifier,
                                        int effectiveStart,
                                        int maxUsedSerial) {

        return new StartSerialRowView(
                classifier.getId(),
                classifier.getCode(),
                classifier.getProductType(),
                classifier.getFullDisplayName(),
                SerialNumbering.keyFor(classifier),
                numberingLabel(classifier),
                classifier.getStartSerial(),
                effectiveStart,
                maxUsedSerial
        );
    }

    /** Номер, который выдаст система при следующем изделии этого ряда. */
    public int nextSerial() {
        return Math.max(maxUsedSerial + 1, effectiveStart);
    }

    /**
     * Строка для клиентского фильтра таблицы.
     *
     * Раздел перечисляет сотни изделий, и запрос на каждое набранное
     * слово был бы дороже самой страницы.
     */
    public String searchText() {
        return (code == null ? "" : code) + " " + displayName;
    }

    /**
     * Стартовый номер задан — поле держит осмысленное значение.
     *
     * Пустоту раздел показывает как «не задан»: именно так читает её
     * выдача номера (см. {@code ProductClassifier#getStartSerialOrOne}).
     */
    public boolean isStartSet() {
        return startSerial != null && startSerial >= 1;
    }

    /**
     * Ряд уже дошёл дальше заданного старта.
     *
     * Нужно предупреждением в таблице, а не отказом при сохранении:
     * максимум мог вырасти между открытием страницы и сохранением, и
     * отклонять правку из-за этого значило бы не дать администратору
     * убрать заведомо избыточный старт.
     */
    public boolean isStartBelowUsed() {
        return isStartSet() && effectiveStart <= maxUsedSerial;
    }

    /**
     * Старт задан другому классификатору того же рода.
     *
     * Для датчиков ряда «на один классификатор» не бывает: показывать
     * строке её собственное значение без пометки, что рядом стоит чужое,
     * означало бы два числа на один и тот же ряд.
     */
    public boolean isStartInherited() {
        return productType == ProductType.SENSOR
                && startSerial != null
                && startSerial != effectiveStart;
    }

    private static String numberingLabel(ProductClassifier classifier) {
        if (classifier.getProductType() == ProductType.SENSOR
                && classifier.getSensorCatalog() != null) {
            return "род «" + classifier.getSensorCatalog().getName() + "»";
        }

        return "классификатор";
    }
}
