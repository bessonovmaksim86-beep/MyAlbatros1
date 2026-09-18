(() => {
    'use strict';

    /*
     * Уровень заказа: выбор родительского выпуска (ЗП) превращает
     * форму в заказ-комплектующий (ЗНП).
     *
     * Различия формы, которые скрипт и отражает:
     *   — заказ покупателя у комплектующего наследуется от родителя
     *     и не выбирается (он уже занят этим ЗП, в списке свободных
     *     его нет);
     *   — номер не автогенерируется, а вводится вручную: он приходит
     *       из внешнего учёта;
     *   — состава выпуска нет, серийники не выдаются.
     *
     * Сервис эти правила проверяет при сохранении — скрипт только
     * убирает из формы поля, которые всё были бы проигнорированы.
     */
    function initializeProductionLevel() {
        const parentSelect =
            document.getElementById('parentProductionOrderId');

        const customerSelect =
            document.getElementById('customerOrderId');

        const numberInput = document.getElementById('number');

        const hint = document.getElementById('poParamsHint');

        const previewBody =
            document.getElementById('poPreviewBody');

        if (!parentSelect || !customerSelect) {
            return;
        }

        const editing = previewBody
            ? previewBody.getAttribute('data-editing') === 'true'
            : false;

        const ROOT_HINT =
            'Номер формируется автоматически и уникален';

        const COMPONENT_HINT =
            'Номер комплектующего вводится вручную; заказ покупателя '
            + 'и срок берутся из выбранного заказа верхнего уровня, '
            + 'серийные номера не выдаются';

        /*
         * При правке уровень заказа неизменный, а родитель передаётся
         * скрытым полем. Поля формы трогать нельзя: автоочистка
         * «сгенерированного» номера стёрла бы номер уже созданного
         * комплектующего, который для него и является штатным.
         */
        if (editing) {
            if (hint) {
                hint.textContent = parentSelect.value
                    ? COMPONENT_HINT
                    : ROOT_HINT;
            }

            return;
        }

        /* Автоподобранный номер формы: он возвращается,
           когда родитель снова не выбран. */
        const generatedNumber = numberInput
            ? numberInput.value
            : '';

        function apply() {
            const component = parentSelect.value !== '';

            customerSelect.disabled = component;

            if (hint) {
                hint.textContent = component
                    ? COMPONENT_HINT
                    : ROOT_HINT;
            }

            if (numberInput) {
                if (component) {
                    /*
                     * Сгенерированный номер убирается: он относится
                     * к выпуску, а комплектующему нужен собственный.
                     */
                    if (numberInput.value === generatedNumber) {
                        numberInput.value = '';
                    }
                } else if (!numberInput.value.trim()) {
                    numberInput.value = generatedNumber;
                }
            }

            /*
             * Состав выпуска есть только у заказа верхнего уровня,
             * поэтому предпросмотр по заказу покупателя для
             * комплектующего скрывается.
             */
            if (previewBody && component) {
                previewBody.innerHTML = '';

                const row = document.createElement('tr');
                const cell = document.createElement('td');

                cell.colSpan = 6;
                cell.className = 'empty-table';
                cell.textContent =
                    'У заказа-комплектующего состава выпуска нет: '
                    + 'он сам входит внутрь изделия';

                row.appendChild(cell);
                previewBody.appendChild(row);
            }
        }

        parentSelect.addEventListener('change', apply);

        apply();
    }

    /*
     * Предпросмотр состава выпуска.
     *
     * Состав не вводится вручную — он выводится из позиций выбранного
     * заказа покупателя, поэтому диспетчер видит заранее, сколько
     * экземпляров и с какими кодами изделий получит заказ. Серийные
     * номера здесь не показываются: они выдаются при сохранении.
     */
    function initializeProductionPreview() {
        const script = document.querySelector(
            'script[data-preview-url]'
        );

        const previewUrl = script
            ? script.getAttribute('data-preview-url')
            : null;

        const customerOrderSelect =
            document.getElementById('customerOrderId');

        const body = document.getElementById('poPreviewBody');

        if (!previewUrl || !customerOrderSelect || !body) {
            return;
        }

        const editing = body.getAttribute('data-editing') === 'true';

        function emptyRow(text) {
            const row = document.createElement('tr');
            const cell = document.createElement('td');

            cell.colSpan = 6;
            cell.className = 'empty-table';
            cell.textContent = text;
            row.appendChild(cell);

            return row;
        }

        function render(rows) {
            body.innerHTML = '';

            if (!rows || rows.length === 0) {
                body.appendChild(
                    emptyRow(
                        'В выбранном заказе покупателя нет изделий — '
                        + 'выпустится пустой заказ'
                    )
                );

                return;
            }

            rows.forEach((row, index) => {
                const tr = document.createElement('tr');

                const values = [
                    index + 1,
                    row.classifierCode,
                    row.productTypeName,
                    row.productName,
                    row.quantity,
                    row.instances + ' шт.'
                ];

                values.forEach((value) => {
                    const td = document.createElement('td');

                    td.textContent =
                        value === null || value === undefined
                            ? '—'
                            : String(value);

                    tr.appendChild(td);
                });

                body.appendChild(tr);
            });
        }

        function load(customerOrderId) {
            if (!customerOrderId) {
                body.innerHTML = '';
                body.appendChild(
                    emptyRow(
                        'Выберите заказ покупателя, чтобы увидеть '
                        + 'состав выпуска'
                    )
                );

                return;
            }

            const url = previewUrl
                + '?customerOrderId='
                + encodeURIComponent(customerOrderId);

            fetch(url, {
                headers: {'Accept': 'application/json'}
            })
                .then((response) =>
                    response.ok ? response.json() : [])
                .then(render)
                .catch(() => {
                    body.innerHTML = '';
                    body.appendChild(
                        emptyRow('Не удалось загрузить состав выпуска')
                    );
                });
        }

        /*
         * В режиме правки состав уже закреплён серийными номерами,
         * поэтому предпросмотр по заказу покупателя не перезаписывает
         * пустую строку — данные состава видны в карточке заказа.
         */
        if (editing) {
            return;
        }

        customerOrderSelect.addEventListener(
            'change',
            () => load(customerOrderSelect.value)
        );

        if (customerOrderSelect.value) {
            load(customerOrderSelect.value);
        }
    }

    /*
     * Выбор датчиков для заказа-комплектующего.
     *
     * Серийный номер выпускает заказ верхнего уровня, а ЗНП только
     * выбирается среди уже выпущенных — поэтому список подгружается
     * по выбранному родителю и чужие номера в него не попадают.
     *
     * При правке варианты и отмеченный состав приходят с сервера, и
     * перезаписывать их нельзя: отметки отражают уже сохранённую
     * привязку, а запрос вернул бы её же без отметок.
     */
    function initializeSerialPicker() {
        const container =
            document.getElementById('serialPickerOptions');

        const parentSelect =
            document.getElementById('parentProductionOrderId');

        if (!container || !parentSelect) {
            return;
        }

        const script = document.querySelector(
            'script[data-serials-url]'
        );

        const serialsUrl = script
            ? script.getAttribute('data-serials-url')
            : null;

        const editing = document.body.getAttribute(
            'data-po-editing'
        ) === 'true';

        const EMPTY_HINT =
            'Сначала выберите заказ верхнего уровня — датчики '
            + 'берутся из его выпуска.';

        /* Отмеченные сервером составы восстанавливаются по id. */
        const checked = new Set(
            Array.prototype.map.call(
                container.querySelectorAll(
                    'input[name="serialIds"]:checked'
                ),
                (input) => input.value
            )
        );

        function message(text) {
            container.innerHTML = '';

            const p = document.createElement('p');

            p.className = 'po-serials-empty';
            p.textContent = text;
            container.appendChild(p);
        }

        function render(rows) {
            if (!rows || rows.length === 0) {
                message(
                    'В выбранном заказе верхнего уровня нет '
                    + 'выпущенных датчиков.'
                );

                return;
            }

            container.innerHTML = '';

            rows.forEach((row) => {
                const label = document.createElement('label');

                label.className = 'po-serial';

                const input = document.createElement('input');

                input.type = 'checkbox';
                input.name = 'serialIds';
                input.value = row.id;
                input.checked = checked.has(String(row.id));

                const number = document.createElement('span');

                number.className = 'po-serial-number';
                number.textContent = row.number;

                const name = document.createElement('span');

                name.className = 'po-serial-name';
                name.textContent = row.label;

                label.appendChild(input);
                label.appendChild(number);
                label.appendChild(name);
                container.appendChild(label);
            });
        }

        function load(parentId) {
            if (!serialsUrl) {
                return;
            }

            if (!parentId) {
                message(EMPTY_HINT);
                return;
            }

            fetch(
                serialsUrl + '?parentId='
                + encodeURIComponent(parentId),
                {headers: {'Accept': 'application/json'}}
            )
                .then((response) =>
                    response.ok ? response.json() : [])
                .then(render)
                .catch(() => message(
                    'Не удалось загрузить номера датчиков'
                ));
        }

        if (editing || !serialsUrl) {
            return;
        }

        /*
         * Смена родителя обнуляет отметки: номера другого выпуска
         * не могут войти в этот заказ, а сохранение чужого id сервис
         * всё равно отклонит.
         */
        parentSelect.addEventListener('change', () => {
            checked.clear();
            load(parentSelect.value);
        });

        if (parentSelect.value) {
            load(parentSelect.value);
        }
    }

    function initialize() {
        initializeProductionLevel();
        initializeProductionPreview();
        initializeSerialPicker();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        initialize();
    }
})();
