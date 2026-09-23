package ru.company.production.dto;

import java.util.List;

/**
 * Итог проверки отсканированной партии ячеек.
 *
 * Результат нужен не только как «да/нет»: оператор обязан увидеть,
 * чего именно не хватает и что задвоено, иначе он не поймёт, какую
 * ячейку искать. Поэтому в записи идут три отдельных списка —
 * отсутствующие, повторные и посторонние номера.
 *
 * @param accepted   партия принята целиком, ячейки записаны в реестр
 * @param missing    номера партии, которые так и не отсканированы
 * @param duplicates номера, отсканированные более одного раза
 * @param foreign    распознанные номера, не принадлежащие партии
 * @param malformed  строки, из которых не удалось прочитать номер
 * @param message    готовая надпись для оператора
 */
public record CellScanningResult(
        boolean accepted,
        List<String> missing,
        List<String> duplicates,
        List<String> foreign,
        List<String> malformed,
        String message
) {

    /*
     * Фабричный метод не может называться accepted(): record уже
     * объявляет аксессор accepted() для компонента с тем же именем, и
     * два метода с разным возвращаемым типом несовместимы.
     */
    public static CellScanningResult acceptedResult() {
        return new CellScanningResult(
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }
}
