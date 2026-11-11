/**
 * Отправка возврата на доработку.
 *
 * Кнопки различают действие атрибутом formaction, поэтому проверка
 * общая: без отмеченных строк сервер вернул бы отказ уже после
 * подтверждения, а пустая причина лишила бы исполнителя ответа
 * «что переделывать».
 *
 * Подтверждение нужно обоим действиям: снятие проведения — это
 * отмена принятой работы, и сделать это одним кликом не должно
 * быть возможным.
 */
document.addEventListener('DOMContentLoaded', function () {

    var form = document.getElementById('reworkForm');

    if (!form) {
        return;
    }

    form.addEventListener('submit', function (event) {

        var checked = form.querySelectorAll(
            'input[name="rowIds"]:checked'
        );

        if (checked.length === 0) {
            event.preventDefault();
            alert('Отметьте операции, которые возвращаете на доработку.');
            return;
        }

        var reason = document.getElementById('reworkReason');

        if (!reason || !reason.value.trim()) {
            event.preventDefault();
            alert('Укажите причину возврата: исполнитель обязан видеть, '
                    + 'что переделывать.');
            if (reason) {
                reason.focus();
            }
            return;
        }

        var button = event.submitter;

        /*
         * formAction — то, куда реально уйдёт запрос: браузер подставляет
         * его из кнопки, на которой нажали.
         */
        var toPredecessor = button
                && String(button.formAction || '').indexOf('send-to-predecessor') >= 0;

        var message = toPredecessor
                ? 'Отмеченных операций: ' + checked.length
                    + ' — изделие уйдёт в предшествующий цех, а проведение'
                    + ' снимется со всех проведённых операций этого цеха.'
                    + ' Отправить?'
                : 'Отмеченных операций: ' + checked.length
                    + ' — проведение будет снято, и работы вернутся'
                    + ' исполнителям. Отправить на доработку?';

        if (!confirm(message)) {
            event.preventDefault();
        }
    });
});
