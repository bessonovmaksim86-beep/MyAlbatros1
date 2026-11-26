(() => {
    'use strict';

    /*
     * Плановый расчёт выполнения.
     *
     * Позиций может быть несколько. Как только диспетчер выбирает
     * изделие в последней строке, ниже автоматически появляется
     * ещё одна пустая позиция; кнопка «Добавить позицию» делает
     * то же самое вручную. Пустые строки при отправке
     * отбрасываются, чтобы не мешать расчёту.
     */

    const MAX_ITEMS = 20;

    function rows(container) {
        return Array.from(
            container.querySelectorAll('.forecast-item'));
    }

    function refreshRemoveButtons(container) {
        const list = rows(container);
        const visible = list.length > 1;

        list.forEach((row) => {
            const button = row.querySelector(
                '.forecast-item__remove');

            if (button) {
                button.hidden = !visible;
            }
        });
    }

    function resetRow(row) {
        const select = row.querySelector(
            'select[name="classifierIds"]');
        const quantity = row.querySelector(
            'input[name="quantities"]');

        if (select) {
            select.value = '';
        }

        if (quantity) {
            quantity.value = '1';
        }
    }

    function addItem(container, template, focus) {
        if (rows(container).length >= MAX_ITEMS) {
            return;
        }

        const fragment = template.content.cloneNode(true);
        const row = fragment.querySelector('.forecast-item');

        if (!row) {
            return;
        }

        resetRow(row);
        container.appendChild(fragment);
        refreshRemoveButtons(container);

        if (focus) {
            const select = row.querySelector(
                'select[name="classifierIds"]');

            if (select) {
                select.focus();
            }
        }
    }

    function removeItem(container, row) {
        if (rows(container).length <= 1) {
            return;
        }

        row.remove();
        refreshRemoveButtons(container);
    }

    function dropEmptyRows(container) {
        const list = rows(container);

        const filled = list.filter((row) => {
            const select = row.querySelector(
                'select[name="classifierIds"]');

            return select && select.value !== '';
        });

        if (filled.length === 0) {
            return;
        }

        list.forEach((row) => {
            if (!filled.includes(row)) {
                row.remove();
            }
        });
    }

    document.addEventListener('DOMContentLoaded', () => {
        const container =
            document.getElementById('forecast-items');
        const template =
            document.getElementById('forecast-item-template');
        const addButton =
            document.getElementById('forecast-item-add');
        const form =
            document.getElementById('forecast-form');

        if (!container || !template) {
            return;
        }

        refreshRemoveButtons(container);

        if (addButton) {
            addButton.addEventListener('click', () => {
                addItem(container, template, true);
            });
        }

        /*
         * Выбор изделия в последней строке добавляет следующую
         * пустую позицию — вводить ещё одно изделие можно сразу.
         */
        container.addEventListener('change', (event) => {
            const select = event.target.closest(
                'select[name="classifierIds"]');

            if (!select || select.value === '') {
                return;
            }

            const list = rows(container);
            const row = select.closest('.forecast-item');

            if (!row || list[list.length - 1] !== row) {
                return;
            }

            addItem(container, template, false);
        });

        container.addEventListener('click', (event) => {
            const button = event.target.closest(
                '.forecast-item__remove');

            if (!button) {
                return;
            }

            const row = button.closest('.forecast-item');

            if (row) {
                removeItem(container, row);
            }
        });

        if (form) {
            form.addEventListener('submit', () => {
                dropEmptyRows(container);
            });
        }
    });
})();
