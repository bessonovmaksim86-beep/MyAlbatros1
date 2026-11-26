package ru.company.production.dto;

/**
 * Рейтинг исполнителя по завершённым операциям.
 *
 * @param executorId      идентификатор исполнителя
 * @param executorName    ФИО
 * @param doneOperations  завершённых операций за период
 * @param lateOperations  из них просроченных
 * @param reworkOperations из них переделанных
 */
public record ExecutorRankingRow(
        Long executorId,
        String executorName,
        long doneOperations,
        long lateOperations,
        long reworkOperations
) {
}
