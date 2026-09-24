(() => {
    'use strict';

    /*
     * Отметка ячеек изолятора.
     *
     * Счётчик и состояние кнопок считают по чекбоксам, а не держат
     * своё число: строки перечня размечают в обе стороны (вручную и
     * «Все»), и любое расхождение цифры с фактическим составом формы
     * кончилось бы заказом не на тот перечень.
     *
     * Кнопки формирования заказа блокируют при нулевом выборе: пустой
     * состав отклоняет сервис, но руководителю об этом скажет серый вид
     * кнопки, а не отказ после отправки длинной формы.
     */
    function initializePicking() {
        const form = document.getElementById('skPickedForm');

        if (!form) {
            return;
        }

        const boxes = Array.from(
            form.querySelectorAll('.sk-check__input')
        );

        const checkAll = document.getElementById('skCheckAll');
        const counters = [
            document.getElementById('skPickedCount'),
            document.getElementById('skPickedCountBottom')
        ].filter((element) => element !== null);

        const submitButtons = [
            document.getElementById('skCreateOrderBtn'),
            document.getElementById('skCreateOrderBtnBottom')
        ].filter((element) => element !== null);

        const clearButton = document.getElementById('skClearPicked');

        /* Строки «уже в заказе» заблокированы и в отбор не входят. */
        const pickable = boxes.filter((box) => !box.disabled);

        function selectedCount() {
            return pickable.filter((box) => box.checked).length;
        }

        function render() {
            const count = selectedCount();

            counters.forEach((counter) => {
                counter.textContent = 'Отмечено ячеек: ' + count;
            });

            submitButtons.forEach((button) => {
                button.disabled = count === 0;
            });

            if (checkAll) {
                checkAll.checked =
                    pickable.length > 0 && count === pickable.length;

                /*
                 * Состояние «наполовину отмечено» показывают галочкой с
                 * тире: без него заголовок таблицы выглядел бы пустым,
                 * хотя часть перечня уже размечена.
                 */
                checkAll.indeterminate = count > 0 && count < pickable.length;
                checkAll.disabled = pickable.length === 0;
            }

            /*
             * Отмеченную строку подсвечивают классом: перечень длинный,
             * и без подсветки отмеченное теряется при прокрутке.
             */
            boxes.forEach((box) => {
                const row = box.closest('tr');

                if (row) {
                    row.classList.toggle('is-picked', box.checked);
                }
            });
        }

        form.addEventListener('change', (event) => {
            if (event.target === checkAll) {
                pickable.forEach((box) => {
                    box.checked = checkAll.checked;
                });
            }

            render();
        });

        if (clearButton) {
            clearButton.addEventListener('click', () => {
                boxes.forEach((box) => {
                    box.checked = false;
                });

                render();
            });
        }

        render();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializePicking);
    } else {
        initializePicking();
    }
})();
