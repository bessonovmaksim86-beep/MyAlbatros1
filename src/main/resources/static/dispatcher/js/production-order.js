(() => {
    'use strict';

    /*
     * Синхронизация полей состава ЗНП.
     *
     * Её задаёт initializeZnpKind, а вызывает initializeProductionMode:
     * набор полей зависит от вида, а вид при открытии формы уже выбран
     * сервером (значение селектора). Держать общую логику внутри одного
     * обработчика нельзя — второй о ней не узнает, поэтому функция
     * живёт в области видимости модуля.
     */
    let syncZnpFields = null;

    /*
     * Режим формы: выпуск (ЗП) или заказ-комплектующий (ЗНП).
     *
     * Режим не выбирается в странице — его задаёт кнопка, по которой
     * форму открыли, и приходит скрытым полем orderLevel. Скрипту от
     * режима нужны два внешних отличия, которые иначе пришлось бы
     * держать в шаблоне отдельными ветками:
     *   — у выпуска номер формируется автоматически, у комплектующего
     *     вводится вручную (он приходит из внешнего учёта);
     *   — состав выпуска показывается только выпуску: комплектующий сам
     *     входит внутрь изделия, и таблица позиций для него пуста.
     *
     * Сервис эти правила проверяет при сохранении — скрипт только
     * убирает с экрана то, что всё равно было бы проигнорировано.
     */
    function initializeProductionMode() {
        const hint = document.getElementById('poParamsHint');

        const levelInput = document.querySelector(
            'input[name="orderLevel"]'
        );

        const component = levelInput
            ? levelInput.value === 'COMPONENT'
            : false;

        const COMPONENT_HINT =
            'Номер заказа-комплектующего вводится вручную по данным '
            + 'внешнего учёта; серийные номера не выдаются';

        /*
         * Подсказка о способе задавать номер зависит от режима, а режим
         * известен только здесь: сервер его в текст не подставляет,
         * потому что форма одна для обоих случаев.
         */
        if (hint && component) {
            hint.textContent = COMPONENT_HINT;
        }

        /*
         * Набор полей состава перестраивается по виду заказа. Вызов
         * обязателен и при правке: сервер скрыл ненужные поля, но
         * блокировка заказчика (наследование от выпуска) зависит от
         * выбранного вида и существует только на стороне скрипта.
         */
        if (syncZnpFields) {
            syncZnpFields();
        }
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

        /*
         * Секция состава скрыта сервером у заказа-комплектующего:
         * позиций выпуска у него нет. Слушатель заказчика при этом
         * остаётся активным (групповому и ремонтному заказу заказчик
         * выбирается в форме), поэтому без выхода здесь каждый выбор
         * заказчика дёргал бы предпросмотр ради невидимой таблицы.
         */
        const previewSection =
            document.getElementById('releasePreviewSection');

        if (previewSection && previewSection.hidden) {
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

            /*
             * Групповому и ремонтному заказу перечень датчиков не
             * показывается (см. initializeZnpKind), поэтому и номера
             * выпуска для него не запрашивают: лишний запрос к чужому
             * выпуску при каждом выборе родителя был бы заметен
             * диспетчеру, который работает с группой ячеек.
             */
            const pickerField =
                document.getElementById('serialPickerField');

            if (pickerField && pickerField.hidden) {
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

    /*
     * Техпроцесс группового заказа (миграция V40).
     *
     * Маршрут выбирают по изделию: список применимых к изделию
     * техпроцессов отдаёт endpoint /production-order/classifier-routes.
     * Скрипт нужен потому, что изделие выбирают уже в открытой форме,
     * а держать в странице маршруты всех изделий — значит предлагать
     * диспетчеру чужие маршруты.
     *
     * Смена изделия сбрасывает выбранный маршрут: прежний к новому
     * изделию не применим, и сервис такое сохранение отклонил бы, а
     * показывать несработавший выбор — значит заставлять диспетчера
     * разбираться с ошибкой вместо того, чтобы просто выбрать маршрут.
     *
     * При правке начальная выборка приходит с сервера (маршрут заказа
     * мог быть снят из работы, и в endpoint-список он бы не попал), но
     * слушатель изделия остаётся: изделие при правке менять можно.
     */
    function initializeTechProcess() {
        const classifierSelect =
            document.getElementById('productClassifierId');

        const techSelect = document.getElementById('techProcessId');

        if (!classifierSelect || !techSelect) {
            return;
        }

        const script = document.querySelector(
            'script[data-routes-url]'
        );

        const routesUrl = script
            ? script.getAttribute('data-routes-url')
            : null;

        const editing = document.body.getAttribute(
            'data-po-editing'
        ) === 'true';

        function message(text) {
            techSelect.innerHTML = '';

            const option = document.createElement('option');

            option.value = '';
            option.textContent = text;
            techSelect.appendChild(option);
        }

        function render(rows) {
            techSelect.innerHTML = '';

            const placeholder = document.createElement('option');

            placeholder.value = '';
            placeholder.textContent = '— выберите техпроцесс —';
            techSelect.appendChild(placeholder);

            if (!rows || rows.length === 0) {
                const option = document.createElement('option');

                option.value = '';
                option.textContent =
                    'У изделия нет действующих техпроцессов';
                techSelect.appendChild(option);

                return;
            }

            rows.forEach((row) => {
                const option = document.createElement('option');

                option.value = row.id;
                option.textContent =
                    row.code
                    + (row.basic ? ' — базовый' : '')
                    + ' (' + row.operationCount + ' оп.)';

                techSelect.appendChild(option);
            });
        }

        function load(classifierId) {
            if (!routesUrl) {
                return;
            }

            if (!classifierId) {
                message('Сначала выберите изделие');
                return;
            }

            /*
             * Для комплектования блок скрыт или не отрисован: маршруты
             * чужого вида запрашивать незачем.
             */
            const techField =
                document.getElementById('kindTechProcessField');

            if (techField && techField.hidden) {
                return;
            }

            message('Загружаем техпроцессы…');

            fetch(
                routesUrl + '?classifierId='
                + encodeURIComponent(classifierId),
                {headers: {'Accept': 'application/json'}}
            )
                .then((response) =>
                    response.ok ? response.json() : [])
                .then(render)
                .catch(() => message(
                    'Не удалось загрузить техпроцессы'
                ));
        }

        classifierSelect.addEventListener('change', () => {
            load(classifierSelect.value);
        });

        /*
         * Первая загрузка только при создании: при правке список и
         * выбранный маршрут уже в странице, и перезапись стёрла бы
         * выбор заказа.
         */
        if (!editing && classifierSelect.value) {
            load(classifierSelect.value);
        }
    }

    /*
     * Вид заказа-комплектующего (миграция V37).
     *
     * Вид решает, ЧЕМ задан состав заказа:
     *   — комплектование  → перечень серийных номеров выпуска;
     *   — групповые и ремонтные виды → изделие и количество.
     *
     * Поэтому переключение вида меняет набор полей формы. Скрипт только
     * убирает с экрана поля, которые сервис всё равно проигнорировал бы
     * (см. ZnpKind.isSerialBased) — обязательность проверяет сервис.
     *
     * Справочник изделий подгружается по виду: тип изделия ограничен
     * назначением заказа (ячейка / прибор), и держать в странице все
     * справочники сразу — значит предлагать диспетчеру чужие изделия.
     */
    function initializeZnpKind() {
        const kindSelect = document.getElementById('znpKind');

        if (!kindSelect) {
            return;
        }

        const serialField = document.getElementById('serialPickerField');
        const classifierField =
            document.getElementById('kindClassifierField');
        const quantityField = document.getElementById('kindQuantityField');
        const classifierSelect =
            document.getElementById('productClassifierId');
        const customerSelect =
            document.getElementById('customerOrderId');
        const parentField = document.getElementById('parentField');

        /*
         * Режим формы задаёт кнопка, открывшая её, а не выбор в
         * странице: выпуск (ЗП) создаётся из заказа покупателя,
         * комплектующий (ЗНП) — из этого окна. Скрытое поле orderLevel
         * приходит с сервера, и скрипту из него достаточно понять,
         * выбирается ли заказчик или наследуется от выпуска.
         */
        const levelInput = document.querySelector(
            'input[name="orderLevel"]'
        );

        const component = levelInput
            ? levelInput.value === 'COMPONENT'
            : false;

        const script = document.querySelector(
            'script[data-kind-classifiers-url]'
        );

        const classifiersUrl = script
            ? script.getAttribute('data-kind-classifiers-url')
            : null;

        const editing = document.body.getAttribute(
            'data-po-editing'
        ) === 'true';

        /*
         * Комплектование — значение по умолчанию и для старых заказов,
         * и для пустого выбора: тот же разбор, что в сервисе
         * (ZnpKind.ofNullable), иначе форма и сервис разошлись бы на
         * первом же неочевидном значении.
         */
        const SERIAL_BASED = 'KITTING';

        let loadedKind = null;

        function isSerialBased() {
            return (kindSelect.value || SERIAL_BASED) === SERIAL_BASED;
        }

        function classifierMessage(text) {
            if (!classifierSelect) {
                return;
            }

            classifierSelect.innerHTML = '';

            const option = document.createElement('option');

            option.value = '';
            option.textContent = text;
            classifierSelect.appendChild(option);
        }

        function render(rows, wanted) {
            classifierSelect.innerHTML = '';

            const placeholder = document.createElement('option');

            placeholder.value = '';
            placeholder.textContent = '— выберите изделие —';
            classifierSelect.appendChild(placeholder);

            if (!rows || rows.length === 0) {
                const option = document.createElement('option');

                option.value = '';
                option.textContent =
                    'В справочнике нет изделий этого типа';
                classifierSelect.appendChild(option);

                return;
            }

            rows.forEach((row) => {
                const option = document.createElement('option');

                option.value = row.id;
                option.textContent =
                    row.code + ' — ' + (row.name || '');

                /* Выбранное ранее значение сохраняем по id. */
                if (wanted && String(wanted) === String(row.id)) {
                    option.selected = true;
                }

                classifierSelect.appendChild(option);
            });
        }

        function loadClassifiers() {
            if (!classifiersUrl || !classifierSelect) {
                return;
            }

            /*
             * При правке справочник и выбранное изделие приходят с
             * сервера: перезаписывать их ответом endpoint'а нельзя —
             * изделие, снятое из выбора, исчезло бы из формы, а вместе
             * с ним и состав заказа при сохранении.
             */
            if (editing) {
                return;
            }

            const kind = kindSelect.value || SERIAL_BASED;

            /* Справочник одного вида грузится один раз. */
            if (loadedKind === kind) {
                return;
            }

            loadedKind = kind;

            const wanted = classifierSelect.value;

            classifierMessage('Загружаем изделия…');

            fetch(
                classifiersUrl + '?kind=' + encodeURIComponent(kind),
                {headers: {'Accept': 'application/json'}}
            )
                .then((response) =>
                    response.ok ? response.json() : [])
                .then((rows) => render(rows, wanted))
                .catch(() => {
                    loadedKind = null;
                    classifierMessage('Не удалось загрузить изделия');
                });
        }

        function applyKind() {
            const serialBased = isSerialBased();

            /*
             * Заказ покупателя наследуется от выпуска только заказу на
             * комплектование: он вписывается внутрь конкретного ЗП, и
             * заказчик у него уже занят этим выпуском — в списке
             * свободных заказов не числится.
             *
             * Групповому и ремонтному заказам наследовать не от чего (у
             * них нет родителя), поэтому заказчик им выбирается в форме,
             * и блокировать поле нельзя.
             */
            const inheritsCustomer = component && serialBased;

            if (customerSelect) {
                customerSelect.disabled = inheritsCustomer;
            }

            /*
             * Заказ верхнего уровня и перечень его датчиков нужны только
             * комплектованию: групповой и ремонтный заказы самостоятельны
             * и задаются изделием с количеством, а выпуску выбор
             * родителя не предлагается вовсе.
             */
            const showParent = component && serialBased;
            const showGroup = component && !serialBased;

            if (parentField) {
                parentField.hidden = !showParent;
            }

            if (serialField) {
                serialField.hidden = !showParent;
            }

            if (classifierField) {
                classifierField.hidden = !showGroup;
            }

            if (quantityField) {
                quantityField.hidden = !showGroup;
            }

            /*
             * Снятые при смене вида отметки датчиков в расчёт не идут:
             * поле скрыто, а сервис для группового вида их всё равно не
             * читает. Обратно список не чистят — возврат к
             * комплектованию должен сохранить уже отмеченное.
             */
            if (showGroup) {
                loadClassifiers();
            }
        }

        syncZnpFields = applyKind;

        if (editing) {
            /*
             * При правке вид неизменный: селектор заменён скрытым
             * полем, справочник и состав приходят с сервера, и
             * перезаписывать их нельзя.
             */
            return;
        }

        kindSelect.addEventListener('change', applyKind);
    }

    function initialize() {
        /*
         * Вид читается раньше режима: набор полей состава зависит от
         * вида, а режим лишь решает, блокировать ли заказчика. К моменту
         * вызова initializeProductionMode обработчик уже должен быть
         * назначен и syncZnpFields — задан.
         */
        initializeZnpKind();
        initializeProductionMode();
        initializeProductionPreview();
        initializeSerialPicker();
        initializeTechProcess();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        initialize();
    }
})();
