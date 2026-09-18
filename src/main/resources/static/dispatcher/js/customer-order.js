(() => {
    'use strict';

    /*
     * Форма заказа покупателя.
     *
     * Позиция собирается в каскад: сначала тип изделия
     * (прибор/датчик/ячейка/система), затем само изделие из
     * классификатора, отфильтрованное по типу. Если выбран тип
     * «Система», под строкой позиции раскрывается состав системы —
     * роды датчиков и приборы, из которых она состоит.
     *
     * Исключение — датчик: его не выбирают из списка (датчиков
     * тысячи), а вписывают полное обозначение из КД в поле
     * .co-fullname. Классификатор по этой строке подбирает сервер.
     * Список изделий при этом остаётся запасным выбором: если
     * подбор не удался, диспетчер может выбрать изделие вручную,
     * и тогда сервер использует именно выбор из списка.
     *
     * Единица измерения и срок позиции из формы убраны: количество
     * всегда в штуках (единицу подставляет сервис), срок позиции
     * больше не указывается.
     */

    /*
     * Поля позиции, которые реально уходят в форму. Поиск ведётся по
     * классу, а не по порядку элементов: набор полей у датчика и у
     * остальных типов разный, и позиционный разбор ломался бы при
     * каждом изменении вёрстки строки.
     */
    const itemFields = [
        {name: 'productType', suffix: 'productType', css: '.co-product-type'},
        {name: 'fullName', suffix: 'fullname', css: '.co-fullname'},
        {name: 'productClassifierId', suffix: 'classifier', css: '.co-classifier'},
        {name: 'quantity', suffix: 'quantity', css: '.co-quantity'},
        {name: 'techProcessType', suffix: 'techProcessType', css: '.co-tech-process'},
        {name: 'note', suffix: 'note', css: '.co-note'}
    ];

    function scriptData() {
        const script = document.getElementById('coOrderScript')
            || document.querySelector('script[data-system-components-url]');

        return script
            ? script.getAttribute('data-system-components-url')
            : null;
    }

    function initializeItems(systemComponentsUrl) {
        const body = document.getElementById('coItemsBody');
        const template = document.getElementById('coItemTemplate');
        const addButton = document.getElementById('coAddItem');

        if (!body || !template || !addButton) {
            return;
        }

        /*
         * Индексы в именах полей всегда плотные (0..n-1): при
         * добавлении/удалении строки имена пересчитываются, иначе
         * Spring собрал бы список с null-элементами в пропусках.
         */
        function renameRows() {
            const rows = body.querySelectorAll('.co-item-row');

            rows.forEach((row, index) => {
                const position = row.querySelector('.co-item-position');

                if (position) {
                    position.textContent = index + 1;
                }

                itemFields.forEach((field) => {
                    const control = row.querySelector(field.css);

                    if (!control) {
                        return;
                    }

                    control.name = 'items[' + index + '].' + field.name;
                    control.id = 'items_' + index + '_' + field.suffix;
                });
            });
        }

        /*
         * Вид строки по типу изделия: для датчика — поле полного
         * наименования, для остальных — список классификатора.
         * Список при датчике остаётся запасным выбором и показывается
         * по кнопке «выбрать из списка» (класс show-classifier).
         */
        function syncSensorMode(row) {
            const typeSelect = row.querySelector('.co-product-type');
            const type = typeSelect ? typeSelect.value : '';
            const sensor = type === 'SENSOR';

            row.classList.toggle('is-sensor', sensor);

            if (!sensor) {
                row.classList.remove('show-classifier');
            }
        }

        function toggleClassifier(row) {
            row.classList.toggle('show-classifier');
        }

        /*
         * Оставляет в списке изделий только опции выбранного типа.
         * Если тип не выбран — показываются все изделия.
         */
        function filterClassifierOptions(row) {
            const typeSelect = row.querySelector('.co-product-type');
            const classifier = row.querySelector('.co-classifier');

            if (!typeSelect || !classifier) {
                return;
            }

            const type = typeSelect.value;
            let visibleSelectedKept = false;

            Array.from(classifier.options).forEach((option) => {
                if (!option.value) {
                    return;
                }

                const matches = !type
                    || option.getAttribute('data-type') === type;

                option.hidden = !matches;

                if (option.selected && matches) {
                    visibleSelectedKept = true;
                }
            });

            /*
             * Изделие не подходит под новый тип — сбрасываем выбор,
             * чтобы не отправить изделие чужого типа.
             */
            if (!visibleSelectedKept) {
                classifier.value = '';
            }
        }

        /*
         * Строка состава системы сразу после строки позиции.
         * Создаётся один раз и переиспользуется.
         */
        function systemRow(row) {
            const next = row.nextElementSibling;

            if (next && next.classList.contains('co-system-row')) {
                return next;
            }

            const created = document.createElement('tr');

            created.className = 'co-system-row';

            const columns = row.cells.length;

            const cell = document.createElement('td');

            cell.colSpan = columns;
            created.appendChild(cell);

            row.insertAdjacentElement('afterend', created);

            return created;
        }

        function removeSystemRow(row) {
            const next = row.nextElementSibling;

            if (next && next.classList.contains('co-system-row')) {
                next.remove();
            }
        }

        function renderComponents(row, components) {
            const sysRow = systemRow(row);
            const cell = sysRow.querySelector('td');

            if (!components.length) {
                cell.innerHTML =
                    '<div class="co-system-empty">'
                    + 'Состав системы не задан в классификаторе.'
                    + '</div>';

                return;
            }

            const list = document.createElement('div');

            list.className = 'co-system-components';

            const title = document.createElement('div');

            title.className = 'co-system-components__title';
            title.textContent = 'Состав системы (датчики и приборы)';
            list.appendChild(title);

            components.forEach((component) => {
                const entry = document.createElement('div');

                entry.className = 'co-system-component';

                const isSensor = component.sensorCatalogId != null;
                const kind = isSensor ? 'Датчик' : 'Прибор';
                const label = isSensor
                    ? component.sensorCatalogName
                    : (component.componentCode
                        + ' · '
                        + (component.componentName || ''));

                entry.textContent = kind + ': ' + (label || '—');
                list.appendChild(entry);
            });

            cell.innerHTML = '';
            cell.appendChild(list);
        }

        function loadSystemComponents(row, classifierId) {
            if (!systemComponentsUrl || !classifierId) {
                return;
            }

            const url = systemComponentsUrl
                + '?systemId='
                + encodeURIComponent(classifierId);

            fetch(url, {
                headers: {'Accept': 'application/json'}
            })
                .then((response) =>
                    response.ok ? response.json() : [])
                .then((components) => renderComponents(row, components))
                .catch(() => removeSystemRow(row));
        }

        /*
         * Показывает или скрывает состав системы в зависимости от
         * выбранного типа и изделия строки.
         */
        function syncSystemBlock(row) {
            const typeSelect = row.querySelector('.co-product-type');
            const classifier = row.querySelector('.co-classifier');

            const isSystem = typeSelect
                && typeSelect.value === 'SYSTEM'
                && classifier
                && classifier.value;

            if (isSystem) {
                loadSystemComponents(row, classifier.value);
            } else {
                removeSystemRow(row);
            }
        }

        /*
         * Тип изделия выводится из уже выбранного изделия — это нужно
         * для старых позиций и для случая, когда тип не был выбран явно,
         * но изделие в списке уже выбрано.
         */
        function syncTypeFromClassifier(row) {
            const typeSelect = row.querySelector('.co-product-type');
            const classifier = row.querySelector('.co-classifier');

            if (!typeSelect || !classifier || !classifier.value) {
                return;
            }

            const selected = classifier.options[classifier.selectedIndex];
            const type = selected && selected.getAttribute('data-type');

            if (type) {
                typeSelect.value = type;
            }
        }

        function addItem() {
            const fragment = template.content.cloneNode(true);
            const row = fragment.querySelector('.co-item-row');

            if (!row) {
                return;
            }

            body.appendChild(fragment);

            /*
             * Ссылка row остаётся валидной после вставки fragment,
             * поэтому новую строку можно инициализировать напрямую —
             * без поиска по селектору (среди tr есть и строки состава).
             */
            filterClassifierOptions(row);
            syncSensorMode(row);
            renameRows();
        }

        addButton.addEventListener('click', addItem);

        body.addEventListener('click', (event) => {
            const picker = event.target.closest('[data-co-pick]');

            if (picker) {
                toggleClassifier(picker.closest('.co-item-row'));

                return;
            }

            const button = event.target.closest('[data-co-remove]');

            if (!button) {
                return;
            }

            const row = button.closest('.co-item-row');

            if (row) {
                removeSystemRow(row);
                row.remove();
                renameRows();
            }
        });

        /*
         * Ручной ввод полного наименования датчика отменяет прежний
         * выбор из списка: серверу доверие отдаётся идентификатору,
         * поэтому оставленный в скрытом списке выбор перехватил бы
         * изделие у только что набранного обозначения.
         */
        body.addEventListener('input', (event) => {
            if (!event.target.classList.contains('co-fullname')) {
                return;
            }

            const row = event.target.closest('.co-item-row');
            const classifier = row && row.querySelector('.co-classifier');

            if (classifier && event.target.value.trim()) {
                classifier.value = '';
                row.classList.remove('show-classifier');
            }
        });

        body.addEventListener('change', (event) => {
            const row = event.target.closest('.co-item-row');

            if (!row) {
                return;
            }

            if (event.target.classList.contains('co-product-type')) {
                syncSensorMode(row);
                filterClassifierOptions(row);
                syncSystemBlock(row);
            } else if (event.target.classList.contains('co-classifier')) {
                syncSystemBlock(row);
            }
        });

        /*
         * Первичная инициализация уже отрисованных сервером строк:
         * восстановить тип по изделию, включить нужный вид строки,
         * отфильтровать опции и, если это система, подгрузить её состав.
         */
        body.querySelectorAll('.co-item-row').forEach((row) => {
            syncTypeFromClassifier(row);
            syncSensorMode(row);
            filterClassifierOptions(row);
            syncSystemBlock(row);
        });

        renameRows();
    }

    /*
     * Кнопка «Занести из чек-листа» открывает XML-чек-лист Б24 из
     * поля ссылки. Сейчас это прямая ссылка на XML-файл: интеграция
     * с API Битрикс24 (автоподстановка позиций) появится позже.
     */
    function initializeChecklistLink() {
        const field = document.getElementById('b24ChecklistUrl');
        const link = document.getElementById('coChecklistOpen');

        if (!field || !link) {
            return;
        }

        function refresh() {
            const url = field.value.trim();
            const valid = /^https?:\/\/.+/.test(url);

            link.setAttribute('href', valid ? url : '#');
            link.classList.toggle('is-disabled', !valid);
        }

        field.addEventListener('input', refresh);
        field.addEventListener('change', refresh);

        link.addEventListener('click', (event) => {
            if (link.getAttribute('href') === '#') {
                event.preventDefault();
                field.focus();
            }
        });

        refresh();
    }

    function start() {
        initializeItems(scriptData());
        initializeChecklistLink();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();