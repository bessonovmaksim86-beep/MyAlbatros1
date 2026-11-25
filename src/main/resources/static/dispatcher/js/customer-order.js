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

    function scriptUrls() {
        const script = document.getElementById('coOrderScript')
            || document.querySelector('script[data-sensor-match-url]');

        if (!script) {
            return { sensorMatch: null };
        }

        return {
            sensorMatch: script.getAttribute('data-sensor-match-url')
        };
    }

    function initializeItems(urls) {
        const sensorMatchUrl = urls.sensorMatch;

        const body = document.getElementById('coItemsBody');
        const template = document.getElementById('coItemTemplate');
        const addButton = document.getElementById('coAddItem');

        if (!body || !template || !addButton) {
            return;
        }

        /*
         * Очередь подборов датчиков: таймер на строку и метка ответа.
         * WeakMap не держит удалённые строки, метка отбрасывает ответы
         * запросов, запущенных по уже стёртому обозначению.
         */
        const matchTimers = new WeakMap();
        let matchSequence = 0;

        /*
         * Индексы в именах полей всегда плотные (0..n-1): при
         * добавлении/удалении строки имена пересчитываются, иначе
         * Spring собрал бы список с null-элементами в пропусках.
         */
        function renameRows() {
            const rows = body.querySelectorAll('.co-item-row');

            rows.forEach((row, index) => {
                itemFields.forEach((field) => {
                    const control = row.querySelector(field.css);

                    if (!control) {
                        return;
                    }

                    control.name = 'items[' + index + '].' + field.name;
                    control.id = 'items_' + index + '_' + field.suffix;
                });
            });

            syncGroups(rows);
        }

        /*
         * Позиции состава группируются со своей системой.
         *
         * Строки, идущие сразу после строки «Система», — её состав
         * (лист разбирает «1», «1.1», «1.2» именно так): они получают
         * вложенный номер вида «1.1» и класс группировки, а строка
         * состава подтягивается стилями по соседству. Индексы в
         * именах полей это не затрагивает — сервер по-прежнему видит
         * плотный список позиций, меняется только то, что видит
         * диспетчер.
         *
         * Номер верхнего уровня считается по позициям-изделиям, а не по
         * строкам таблицы: состав своей системы собственным номером не
         * владеет. Иначе вторая система листа получила бы номер «4»
         * (две строки её состава сдвинули бы нумерацию), а лист называет
         * её «2» — и диспетчер терял бы соответствие позиции строке
         * чек-листа.
         */
        function syncGroups(rows) {
            let topLevel = 0;
            let systemNumber = null;
            let partNumber = 0;

            rows.forEach((row) => {
                const typeSelect = row.querySelector('.co-product-type');
                const type = typeSelect ? typeSelect.value : '';
                const position = row.querySelector('.co-item-position');    
                const isSystem = type === 'SYSTEM';
                const isPart = !isSystem
                    && type !== ''
                    && systemNumber !== null;

                row.classList.toggle('is-system-group', isSystem || isPart);
                row.classList.toggle('is-system-part', isPart);

                let label;
                let isGroupStart;

                if (isPart) {
                    partNumber += 1;
                    label = systemNumber + '.' + partNumber;
                    isGroupStart = false;
                } else {
                    topLevel += 1;
                    label = String(topLevel);
                    isGroupStart = true;

                    systemNumber = isSystem ? topLevel : null;
                    partNumber = 0;
                }

                /*
                 * Разделитель рисуется по началу группы, а не по её
                 * концу: группа может быть последней в списке, и тогда
                 * нижняя граница повисла бы в пустоте.
                 */
                row.classList.toggle('is-group-start', isGroupStart);

                if (position) {
                    position.textContent = label;
                }
            });
        }

        /*
         * Классификатор датчика в строке: код подобранного изделия
         * впереди наименования и подпись с причиной, когда изделия нет.
         *
         * Подобранное изделие живёт в списке, который при датчике
         * скрыт, поэтому без кода строка выглядит как непривязанное
         * обозначение: непонятно, нашёлся ли классификатор. Код
         * показывается, пока не открыт сам список (там изделие видно).
         *
         * Подпись «не найден» имеет право появляться только после
         * выполненного подбора (см. requestSensorMatch): до него строка
         * ничего не знает о результате, а прежняя версия показывала её
         * на каждом вводе — и на обозначениях, для которых сервер
         * спокойно подбирает изделие при сохранении. Пока подбор идёт
         * или не запущен, подпись скрыта; пустое обозначение её не
         * вызывает вовсе.
         */
        function syncClassifierTag(row) {
            const tag = row.querySelector('.co-classifier-tag');
            const hint = row.querySelector('.co-classifier-missing');
            const classifier = row.querySelector('.co-classifier');
            const field = row.querySelector('.co-fullname');

            if (!tag) {
                return;
            }

            if (hint && !hint.dataset.defaultText) {
                hint.dataset.defaultText = hint.textContent.trim();
            }

            const sensor = row.classList.contains('is-sensor');
            const selected = sensor && classifier && classifier.value
                ? classifier.options[classifier.selectedIndex]
                : null;
            const checking = row.classList.contains('classifier-checking');

            /*
             * Подпись имеет право существовать только после ответа
             * «не подобрано» (unmatched). Ошибку запроса показывать
             * как «классификатор не найден» нельзя: причина проверки
             * неизвестна, и утверждение снова стало бы ложью.
             */
            const unmatched = row.dataset.checkState === 'unmatched';

            const label = selected
                ? selected.textContent.trim()
                : (row.dataset.matchedLabel || '');

            const visible = sensor
                && !!label
                && !row.classList.contains('show-classifier');

            tag.textContent = visible ? label : '';
            tag.hidden = !visible;

            const missing = sensor
                && !selected
                && !label
                && !checking
                && unmatched
                && !!(field && field.value.trim());

            row.classList.toggle('classifier-missing', missing);

            if (hint) {
                hint.hidden = !missing;

                if (missing) {
                    hint.textContent = row.dataset.problem
                        || hint.dataset.defaultText;
                }
            }
        }

        /*
         * Подбор классификатора по вписанному обозначению — серверным
         * же матчером, что и при сохранении заказа.
         *
         * Без него форма либо молчала о найденном изделии (оно скрыто в
         * списке), либо врала о ненайденном. Ответ сверяется по метке
         * строки: пока диспетчер печатает, прежний запрос обязан быть
         * отброшен, иначе код изделия перескочит на другое обозначение.
         */
        function requestSensorMatch(row) {
            const typeSelect = row.querySelector('.co-product-type');
            const field = row.querySelector('.co-fullname');
            const value = field ? field.value.trim() : '';
            const token = String(++matchSequence);

            row.dataset.matchToken = token;

            if (!sensorMatchUrl
                || !typeSelect
                || typeSelect.value !== 'SENSOR'
                || !value) {
                clearSensorMatch(row);
                return;
            }

            fetch(sensorMatchUrl + '?fullName=' + encodeURIComponent(value), {
                headers: {'Accept': 'application/json'}
            })
                .then((response) => response.ok ? response.json() : null)
                .then((data) => {
                    if (row.dataset.matchToken !== token) {
                        return;
                    }

                    applySensorMatch(row, data);
                })
                .catch(() => {
                    if (row.dataset.matchToken === token) {
                        row.dataset.checkState = 'error';
                        syncClassifierTag(row);
                    }
                });
        }

        function applySensorMatch(row, data) {
            const classifier = row.querySelector('.co-classifier');

            row.classList.remove('classifier-checking');

            if (!data) {
                /*
                 * Ответ не получен (ошибка сервера или сети): причина
                 * подбора неизвестна, поэтому подпись не показывается.
                 */
                row.dataset.checkState = 'error';
                delete row.dataset.problem;
                syncClassifierTag(row);

                return;
            }

            if (!data.matched) {
                if (classifier) {
                    classifier.value = '';
                }

                row.dataset.problem = data.problem ? data.problem : '';
                row.dataset.checkState = 'unmatched';
                delete row.dataset.matchedLabel;

                syncClassifierTag(row);

                return;
            }

            /*
             * Значение устанавливается только если изделие есть в
             * списке строки: список фильтруется по типу, и чужую опцию
             * селектор не примет. Вне списка изделие остаётся виден
             * подписью из ответа, а сохранение подберёт его сервер.
             */
            const option = classifier
                ? Array.from(classifier.options).find((candidate) =>
                    String(candidate.value) === String(data.classifierId))
                : null;

            if (classifier) {
                classifier.value = option && !option.hidden ? option.value : '';
            }

            if (!option) {
                row.dataset.matchedLabel = data.label
                    ? String(data.label)
                    : '';
            } else {
                delete row.dataset.matchedLabel;
            }

            delete row.dataset.problem;
            row.dataset.checkState = 'matched';

            syncClassifierTag(row);
        }

        function clearSensorMatch(row) {
            clearTimeout(matchTimers.get(row));
            matchTimers.delete(row);

            row.classList.remove('classifier-checking');
            delete row.dataset.problem;
            delete row.dataset.matchedLabel;
            delete row.dataset.matchToken;
            delete row.dataset.checkState;

            syncClassifierTag(row);
        }

        /*
         * Подбор запускается с задержкой: иначе запрос уходит на каждый
         * символ печатаемого обозначения. На время задержки строка
         * помечается как проверяемая, чтобы за это время не мелькнуло
         * «классификатор не найден».
         */
        function scheduleSensorMatch(row) {
            const field = row.querySelector('.co-fullname');
            const typeSelect = row.querySelector('.co-product-type');

            if (!sensorMatchUrl
                || !typeSelect
                || typeSelect.value !== 'SENSOR'
                || !field
                || !field.value.trim()) {
                clearSensorMatch(row);
                return;
            }

            row.classList.add('classifier-checking');
            row.dataset.checkState = 'checking';
            syncClassifierTag(row);

            clearTimeout(matchTimers.get(row));
            matchTimers.set(row, setTimeout(() => {
                matchTimers.delete(row);
                requestSensorMatch(row);
            }, 350));
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

            syncClassifierTag(row);
        }

        function toggleClassifier(row) {
            row.classList.toggle('show-classifier');
            syncClassifierTag(row);
        }

        /*
         * Прибор и ячейка не выбирают тип техпроцесса: они делаются
         * заранее по группам ЗНП и привязываются к системе в процессе
         * производства, поэтому в заказе у них остаются только
         * количество и примечание. Класс снимает колонку со строки,
         * сервер для этих типов наличие техпроцесса не требует.
         */
        function syncTechProcessMode(row) {
            const typeSelect = row.querySelector('.co-product-type');
            const type = typeSelect ? typeSelect.value : '';

            row.classList.toggle('no-tech-process',
                type === 'DEVICE' || type === 'CELL');
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
         * Состав системы отдельной справочной строкой под позицией не
         * показывается: изделия состава уже идут самостоятельными
         * строками «1.1», «1.2» своей группы, и повторять их списком
         * под строкой системы смысла нет — блок только разъедал
         * таблицу на две части.
         */

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
            syncTechProcessMode(row);
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
                clearSensorMatch(row);
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
            const row = event.target.closest('.co-item-row');
            if (!row) {
                return;
            }

            if (event.target.classList.contains('co-fullname')) {
                const classifier = row.querySelector('.co-classifier');

                if (classifier && event.target.value.trim()) {
                    classifier.value = '';
                    row.classList.remove('show-classifier');
                }

                /*
                 * Вписанное обозначение проверяется серверным
                 * подбором: строка обязана показать найденное изделие
                 * или его причину, а не молчать до сохранения.
                 */
                scheduleSensorMatch(row);
            }
        });

        body.addEventListener('change', (event) => {
            const row = event.target.closest('.co-item-row');

            if (!row) {
                return;
            }

            if (event.target.classList.contains('co-product-type')) {
                /*
                 * Список фильтруется до вида строки: смена типа могла
                 * сбросить выбранное изделие, и код классификатора
                 * обязан показывать уже итоговый выбор.
                 */
                filterClassifierOptions(row);
                syncSensorMode(row);
                syncTechProcessMode(row);
                syncGroups(body.querySelectorAll('.co-item-row'));
                scheduleSensorMatch(row);
            } else if (event.target.classList.contains('co-classifier')) {
                /*
                 * Выбор из списка — сознательный: подбор по
                 * обозначению отменяется, иначе ответ сервера вернул бы
                 * в строку то изделие, от которого диспетчер только
                 * что отказался.
                 */
                clearSensorMatch(row);
                syncGroups(body.querySelectorAll('.co-item-row'));
            }
        });

        /*
         * Первичная инициализация уже отрисованных сервером строк:
         * восстановить тип по изделию, включить нужный вид строки и
         * отфильтровать опции.
         *
         * Строки датчика с вписанным обозначением и без выбранного
         * изделия проверяются подбором сразу: иначе открытая после
         * неудачного сохранения форма осталась бы без объяснения, а
         * строкам с уже выбранным изделием (заполнен импорт) проверка
         * не нужна — сервер подобрал его до отрисовки.
         */
        body.querySelectorAll('.co-item-row').forEach((row) => {
            syncTypeFromClassifier(row);
            syncSensorMode(row);
            syncTechProcessMode(row);
            filterClassifierOptions(row);

            const classifier = row.querySelector('.co-classifier');
            const field = row.querySelector('.co-fullname');
            const typeSelect = row.querySelector('.co-product-type');

            if (typeSelect
                && typeSelect.value === 'SENSOR'
                && field
                && field.value.trim()
                && !(classifier && classifier.value)) {
                scheduleSensorMatch(row);
            }
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

    /*
     * Кнопка «Загрузить из файла» открывает системный диалог выбора
     * Excel-файла. Как только файл выбран, форма импорта сразу
     * отправляется: сервер разбирает чек-лист Б24 в предпросмотр и
     * возвращает на эту же форму с уже заполненными позициями.
     * Отправка идёт отдельной формой (enctype=multipart), минуя
     * основную форму заказа, поэтому валидность остальных полей
     * на загрузку чек-листа не влияет.
     */
    function initializeImportForm() {
        const form = document.getElementById('coImportForm');
        const button = document.getElementById('coImportBtn');
        const fileInput = document.getElementById('coImportFile');

        if (!form || !button || !fileInput) {
            return;
        }

        button.addEventListener('click', () => {
            fileInput.click();
        });

        fileInput.addEventListener('change', () => {
            if (!fileInput.value) {
                return;
            }

            button.disabled = true;
            button.textContent = 'Загрузка…';
            form.submit();
        });
    }

    function start() {
        initializeItems(scriptUrls());
        initializeChecklistLink();
        initializeImportForm();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();