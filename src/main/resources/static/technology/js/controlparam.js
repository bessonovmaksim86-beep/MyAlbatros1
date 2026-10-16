(() => {
    'use strict';

    /*
     * Строки вариантов контролируемого параметра.
     *
     * Под последним вариантом всегда держат пустую строку: как только
     * технолог её заполняет, появляется следующая. Удаление строки
     * убирает вариант из формы целиком, поэтому сервис не получит его
     * и удалит сохранённое значение.
     *
     * Индексы в именах полей перенумеровывают после каждого изменения
     * списка: Spring MVC собирает список по непрерывной нумерации, и
     * пропуск в середине оборвался бы потерей части вариантов.
     */
    const container =
        document.querySelector('[data-param-values]');

    if (!container) {
        return;
    }

    const rows = container.querySelector('[data-param-value-rows]');
    const typeSelect = document.querySelector('[data-param-type]');
    const nameInput = document.querySelector('[data-param-name]');
    const valuesRequiredMark =
        container.querySelector('[data-param-values-required]');

    /*
     * Ключевое слово режима ячеек приходит из HTML: сервис считает
     * параметр «ячеечным» по подстроке в наименовании, и клиент
     * обязан использовать тот же признак, иначе форма покажет блок
     * вариантов там, где сервис их не сохранит.
     */
    const cellKeyword =
        (container.dataset.paramCellKeyword || '').toLowerCase();

    const isCellName = () =>
        cellKeyword !== ''
        && (nameInput ? nameInput.value.toLowerCase() : '')
            .includes(cellKeyword);

    /*
     * Состав формы определяет тип параметра и его наименование.
     *
     * Варианты нужны только параметру со списком значений. У «поля
     * ввода» значение вписывает исполнитель, у «ячеечного» его берут
     * из состава изделия — в обоих случаях блок скрывают целиком, а
     * поля снимают с отправки (disabled), чтобы незаполненный список
     * не сорвал сохранение.
     */
    const needsValues = () =>
        Boolean(typeSelect)
        && typeSelect.value === 'LIST'
        && !isCellName();

    const syncTypeVisibility = () => {
        const hidden = !needsValues();

        container.hidden = hidden;

        if (valuesRequiredMark) {
            valuesRequiredMark.hidden = hidden;
        }

        rows.querySelectorAll('input').forEach(input => {
            input.disabled = hidden;
        });
    };

    const refreshValues = () => {
        syncTypeVisibility();

        /*
         * Пустая строка варианта обязательна для отправки списка:
         * при возврате к параметру со списком её нужно вернуть, если
         * технолог переключил тип или переименовал параметр, не
         * заполнив ни одного значения.
         */
        if (needsValues()) {
            ensureBlankRow();
            renumber();
        }
    };

    typeSelect?.addEventListener('change', refreshValues);
    nameInput?.addEventListener('input', refreshValues);

    const blankRow = () => {
        const row = document.createElement('div');
        row.className = 'param-value-row';
        row.dataset.paramValueRow = '';

        const id = document.createElement('input');
        id.type = 'hidden';

        const value = document.createElement('input');
        value.type = 'text';
        value.maxLength = 255;
        value.autocomplete = 'off';
        value.placeholder = 'Например: 1 слой (13 мм)';
        value.dataset.paramValueInput = '';

        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'btn secondary param-value-remove';
        remove.dataset.paramValueRemove = '';
        remove.title = 'Удалить вариант';
        remove.innerHTML = '&#10005;';

        row.append(id, value, remove);

        return row;
    };

    const renumber = () => {
        [...rows.children].forEach((row, index) => {
            const [id, value] = row.querySelectorAll('input');

            id.name = `values[${index}].id`;
            value.name = `values[${index}].value`;
        });
    };

    const lastRow = () => rows.lastElementChild;

    const ensureBlankRow = () => {
        const last = lastRow();

        const filled =
            last === null
            || last.querySelector('[data-param-value-input]').value
                .trim() !== '';

        if (filled) {
            rows.appendChild(blankRow());
        }
    };

    rows.addEventListener('input', event => {
        if (!event.target.matches('[data-param-value-input]')) {
            return;
        }

        ensureBlankRow();
    });

    rows.addEventListener('click', event => {
        const button = event.target.closest('[data-param-value-remove]');

        if (!button) {
            return;
        }

        const row = button.closest('[data-param-value-row]');

        if (row) {
            row.remove();
        }

        renumber();
        ensureBlankRow();
    });

    /*
     * Пустую строку добавляют и при отправке: если технолог ввёл
     * последнее значение и сразу нажал «Сохранить», событие input уже
     * отработало, но проверка лишней не будет — строки не дублируются.
     *
     * У параметра без вариантов блок скрыт, и пустая строка не нужна.
     */
    container.closest('form')?.addEventListener('submit', () => {
        if (!needsValues()) {
            return;
        }

        ensureBlankRow();
        renumber();
    });

    renumber();
    ensureBlankRow();
    syncTypeVisibility();
})();
