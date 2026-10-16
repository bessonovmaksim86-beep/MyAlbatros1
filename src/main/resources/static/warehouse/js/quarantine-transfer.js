(() => {
    'use strict';

    /*
     * Возврат перемещённых ячеек в изолятор брака.
     *
     * Строку перемещения снимают, и ячейка исчезает из перечня склада:
     * пока разбирательство по браку не окончено, держать её «в пути»
     * бессмысленно. Операцию подтверждают отдельным диалогом — после
     * возврата перечень склада уже не покажет, что ячейку передавали.
     *
     * Кнопку держат неактивной при пустом выборе: пустой возврат
     * отклоняет сервис, но серый вид кнопки говорит об этом до
     * прокрутки длинного перечня.
     */
    const FORM = document.getElementById('returnForm');

    if (!FORM) {
        return;
    }

    const boxes = Array.from(
        FORM.querySelectorAll('.warehouse-check__input')
    );

    const submit = document.getElementById('returnButton');
    const hint = document.getElementById('returnHint');

    const hintBase = hint ? hint.textContent.trim() : '';

    function selected() {
        return boxes.filter((box) => box.checked);
    }

    function render() {
        const count = selected().length;

        if (submit) {
            submit.disabled = count === 0;
        }

        if (hint) {
            hint.textContent = count > 0
                ? 'Будет возвращено в изолятор брака: ' + count + ' яч.'
                : hintBase;
        }
    }

    FORM.addEventListener('change', render);

    /*
     * Подтверждение берут отдельным запуском: первый клик только
     * показывает диалог, второй — после снятия флага — отправляет
     * форму. Иначе возврат уходил бы молча, а отменить его можно
     * только новым перемещением из изолятора.
     */
    let confirmed = false;

    FORM.addEventListener('submit', (event) => {
        if (confirmed || selected().length === 0) {
            return;
        }

        event.preventDefault();

        const numbers = selected()
            .map((box) => {
                const row = box.closest('tr');

                return row ? row.dataset.number : '';
            })
            .filter((number) => Boolean(number));

        const preview = numbers.length > 6
            ? numbers.slice(0, 6).join(', ') + ' …'
            : numbers.join(', ');

        const answer = window.confirm(
            'Вернуть в изолятор брака '
            + numbers.length
            + ' яч.?\n\n'
            + preview
            + '\n\nОни исчезнут из этого перечня и появятся в изоляторе брака.'
        );

        if (!answer) {
            return;
        }

        confirmed = true;
        FORM.requestSubmit(submit || undefined);
    });

    render();
})();
