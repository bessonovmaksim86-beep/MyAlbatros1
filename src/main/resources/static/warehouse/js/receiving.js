(() => {
    'use strict';

    /*
     * Шаги оприходования: печать QR и сканирование партии.
     *
     * Сканер работает как клавиатура — он печатает номер и отправляет
     * Enter, поэтому поле ввода перехватывает клавишу сама страница:
     * отправка формы на каждом скане закрыла бы приход раньше времени
     * и сбросила бы уже введённые номера.
     */
    const FULL_NUMBER_LENGTH = 9;

    function initializeQrStep() {
        const copyButton = document.getElementById('copyNumbers');
        const status = document.getElementById('copyStatus');
        const numbers = Array.from(
            document.querySelectorAll('#expectedNumbers [data-number], .warehouse-numbers [data-number]'),
            (item) => item.dataset.number
        ).filter(Boolean);

        if (!numbers.length) {
            return;
        }

        if (copyButton) {
            copyButton.addEventListener('click', async () => {
                const text = numbers.join('\n');

                try {
                    if (navigator.clipboard && window.isSecureContext) {
                        await navigator.clipboard.writeText(text);
                    } else {
                        /*
                         * Склад открыт по http-адресу: Clipboard API
                         * там недоступен, поэтому остаётся временный
                         * textarea и execCommand.
                         */
                        const holder = document.createElement('textarea');

                        holder.value = text;
                        holder.setAttribute('readonly', 'readonly');
                        holder.style.position = 'fixed';
                        holder.style.opacity = '0';

                        document.body.appendChild(holder);
                        holder.select();
                        document.execCommand('copy');
                        document.body.removeChild(holder);
                    }

                    showStatus(`Скопировано номеров: ${numbers.length}`);
                } catch (error) {
                    showStatus('Скопировать не удалось — выделите номера вручную');
                }
            });
        }

        /*
         * Печать QR через агента на рабочем месте.
         *
         * Приложение работает на сервере, а BarTender и принтер — на
         * компьютере кладовщика. Кнопка передаёт номера агенту по
         * 127.0.0.1, а агент запускает локальный bartend.exe. После
         * подтверждения агента страница отмечает партию напечатанной
         * на сервере и перезагружает страницу.
         *
         * Если агент не запущен — fetch упадёт, и оператор получит
         * понятную подсказку с предложением запустить run-agent.bat.
         */
        const agentButton = document.getElementById('printViaAgent');

        if (agentButton && numbers.length) {

            /*
             * Готовность агента проверяется сразу при открытии шага.
             *
             * Кнопка остаётся активной, чтобы оператор мог повторить
             * попытку после запуска агента без перезагрузки страницы,
             * но причину он видит заранее: «агент не запущен» или
             * «не задан путь к bartend.exe» — разные действия.
             */
            const healthUrl = agentButton.dataset.agentUrl + '/health';

            fetch(healthUrl, { method: 'GET' })
                .then((response) => response.json())
                .then((health) => {
                    if (!health.ready) {
                        showStatus('Агент печати не готов: '
                            + (health.message || 'проверьте agent.properties'));
                    }
                })
                .catch(() => {
                    showStatus('Агент печати не запущен на этом компьютере — '
                        + 'запустите run-agent.bat');
                });

            agentButton.addEventListener('click', async () => {
                const agentUrl = agentButton.dataset.agentUrl;
                const token = agentButton.dataset.agentToken || '';
                const markUrl = agentButton.dataset.markUrl;
                const classifier = agentButton.dataset.classifier || '';
                const name = agentButton.dataset.name || '';
                const order = agentButton.dataset.order || '';
                const showText = agentButton.dataset.showText === 'true' ? '1' : '0';
                const copies = agentButton.dataset.copies || '1';

                agentButton.disabled = true;
                showStatus('Печать этикеток...');

                const body = [
                    ...numbers.map((number) => 'number=' + number),
                    'classifier=' + classifier,
                    'name=' + name,
                    'order=' + order,
                    'showText=' + showText,
                    'copies=' + copies
                ].join('\n');

                const headers = { 'Content-Type': 'text/plain; charset=UTF-8' };

                if (token) {
                    headers['X-Print-Token'] = token;
                }

                let printResult;

                try {
                    printResult = await fetch(agentUrl + '/print', {
                        method: 'POST',
                        headers: headers,
                        body: body
                    });
                } catch (error) {
                    showStatus('Агент печати не запущен на этом компьютере — '
                        + 'запустите run-agent.bat');
                    agentButton.disabled = false;
                    return;
                }

                let result;

                try {
                    result = await printResult.json();
                } catch (error) {
                    showStatus('Неожиданный ответ агента — '
                        + 'проверьте консоль и файл print-agent.log');
                    agentButton.disabled = false;
                    return;
                }

                if (!result.ok) {
                    showStatus(result.message || 'Печать не выполнена');
                    agentButton.disabled = false;
                    return;
                }

                showStatus('Отметка на сервере...');

                /*
                 * Помечаем партию напечатанной на сервере.
                 * Берём токен из meta, которые ставит серверная
                 * разметка — они одинаковы для всех запросов.
                 */
                const csrfMeta = document.querySelector('meta[name="_csrf"]');
                const csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
                const csrfToken = csrfMeta ? csrfMeta.content : '';
                const csrfHeader = csrfHeaderMeta ? csrfHeaderMeta.content : '_csrf';

                let markResult;

                try {
                    const markHeaders = {};
                    if (csrfToken) {
                        markHeaders[csrfHeader] = csrfToken;
                    }

                    markHeaders['Content-Type'] = 'application/json';

                    markResult = await fetch(markUrl, {
                        method: 'POST',
                        headers: markHeaders,
                        body: JSON.stringify({})
                    });
                } catch (error) {
                    showStatus('Этикетки напечатаны, но отметка не '
                        + 'сохранилась — обновите страницу');
                    agentButton.disabled = false;
                    return;
                }

                if (!markResult.ok) {
                    showStatus('Этикетки напечатаны, но отметка не '
                        + 'сохранилась — обновите страницу');
                    agentButton.disabled = false;
                    return;
                }

                window.location.reload();
            });
        }

        /*
         * Кнопка «Печать QR» — обычная POST-форма: задание уходит на
         * сервер, и только там видно, настроен ли BarTender на этом
         * рабочем месте. Обработка клика здесь означала бы, что печать
         * возможна только при включённом JavaScript.
         */
        function showStatus(text) {
            if (status) {
                status.textContent = text;
            }
        }
    }

    function initializeScanStep() {
        const form = document.getElementById('scanForm');
        const field = document.getElementById('scanField');
        const addButton = document.getElementById('scanAdd');
        const list = document.getElementById('scanList');
        const empty = document.getElementById('scanEmpty');
        const counter = document.getElementById('scanCount');
        const counterBox = document.getElementById('scanCounter');
        const undoButton = document.getElementById('scanUndo');
        const clearButton = document.getElementById('scanClear');

        if (!form || !field || !list) {
            return;
        }

        const expected = new Set(
            Array.from(
                document.querySelectorAll('#expectedNumbers [data-number]'),
                (item) => item.dataset.number
            )
        );

        function normalize(value) {
            return (value || '').trim();
        }

        /*
         * Сканер добавляет пробелы, суффикс Enter и иногда префикс
         * вида «CELL:121200022» — оставляем только цифры, когда их
         * ровно девять, иначе строка остаётся как есть и попадёт в
         * список посторонних.
         */
        function parse(value) {
            const cleaned = normalize(value);
            const digits = cleaned.replace(/\D/g, '');

            return digits.length === FULL_NUMBER_LENGTH ? digits : cleaned;
        }

        function items() {
            return Array.from(list.querySelectorAll('.warehouse-scan-list__item'));
        }

        function valueOf(item) {
            const input = item.querySelector('input[name="scanned"]');

            return normalize(input ? input.value : item.textContent);
        }

        function add(value) {
            const parsed = parse(value);

            if (!parsed) {
                return;
            }

            const item = document.createElement('li');

            item.className = 'warehouse-scan-list__item';

            const input = document.createElement('input');

            input.type = 'hidden';
            input.name = 'scanned';
            input.value = parsed;

            const label = document.createElement('span');

            label.textContent = parsed;

            const remove = document.createElement('button');

            remove.type = 'button';
            remove.className = 'warehouse-scan-list__remove';
            remove.setAttribute('aria-label', 'Убрать номер');
            remove.innerHTML = '&times;';

            item.appendChild(input);
            item.appendChild(label);
            item.appendChild(remove);
            list.appendChild(item);

            field.value = '';
            refresh();
            field.focus();
        }

        function removeItem(item) {
            item.remove();
            refresh();
            field.focus();
        }

        function refresh() {
            const counts = new Map();

            items().forEach((item) => {
                const value = valueOf(item);

                counts.set(value, (counts.get(value) || 0) + 1);
            });

            let matched = 0;

            items().forEach((item) => {
                const value = valueOf(item);

                item.classList.toggle(
                    'is-foreign',
                    !expected.has(value)
                );

                item.classList.toggle(
                    'is-duplicate',
                    (counts.get(value) || 0) > 1 && expected.has(value)
                );
            });

            expected.forEach((number) => {
                if ((counts.get(number) || 0) > 0) {
                    matched++;
                }
            });

            document.querySelectorAll('#expectedNumbers [data-number]')
                .forEach((cell) => {
                    const hits = counts.get(cell.dataset.number) || 0;

                    cell.classList.toggle('is-hit', hits === 1);
                    cell.classList.toggle('is-miss', hits > 1);
                });

            if (counter) {
                counter.textContent = String(matched);
            }

            if (counterBox) {
                counterBox.classList.toggle(
                    'is-complete',
                    expected.size > 0 && matched === expected.size
                );
            }

            if (empty) {
                empty.hidden = items().length > 0;
            }
        }

        field.addEventListener('keydown', (event) => {
            if (event.key === 'Enter') {
                /*
                 * Без preventDefault первый же скан отправил бы форму:
                 * партия проверялась бы по одной ячейке.
                 */
                event.preventDefault();
                add(field.value);
            }
        });

        if (addButton) {
            addButton.addEventListener('click', () => add(field.value));
        }

        list.addEventListener('click', (event) => {
            const button = event.target.closest('.warehouse-scan-list__remove');

            if (button) {
                removeItem(button.closest('.warehouse-scan-list__item'));
            }
        });

        if (undoButton) {
            undoButton.addEventListener('click', () => {
                const itemsList = items();

                if (itemsList.length) {
                    removeItem(itemsList[itemsList.length - 1]);
                }
            });
        }

        if (clearButton) {
            clearButton.addEventListener('click', () => {
                items().forEach((item) => item.remove());
                refresh();
                field.focus();
            });
        }

        /*
         * Номера, возвращённые сервером после неудачной проверки,
         * уже в разметке: их надо посчитать и подсветить, иначе
         * оператор увидит пустой счётчик при неполной партии.
         */
        refresh();
        field.focus();
    }

    /*
     * Отмена партии прихода: мягкое удаление через диалог склада.
     *
     * Кнопка «Удалить» в таблице открывает модальное окно с
     * реквизитами партии — ордер и диапазон номеров — и отправляет
     * форму строки после подтверждения.
     */
    function initializeDeleteDialog() {
        const dialog = document.getElementById('deleteBatchDialog');

        if (!dialog) {
            return;
        }

        const orderEl = document.getElementById('deleteBatchOrder');
        const rangeEl = document.getElementById('deleteBatchRange');
        const confirmBtn = document.getElementById('deleteBatchConfirm');
        const cancelBtn = document.getElementById('deleteBatchCancel');

        let activeForm = null;

        /*
         * Каждая строка таблицы содержит свою форму с action и
         * скрытым полем CSRF: достаточно подменить action и отправить.
         */
        document.querySelectorAll('.warehouse-delete-form').forEach((form) => {

            form.addEventListener('submit', (event) => {
                event.preventDefault();

                const order = form.getAttribute('data-order');
                const range = form.getAttribute('data-range');

                if (orderEl) orderEl.textContent = order;
                if (rangeEl) rangeEl.textContent = range;

                dialog.hidden = false;
                activeForm = form;
            });
        });

        if (confirmBtn) {
            confirmBtn.addEventListener('click', () => {
                if (activeForm) {
                    activeForm.submit();
                }
            });
        }

        if (cancelBtn) {
            cancelBtn.addEventListener('click', () => {
                dialog.hidden = true;
                activeForm = null;
            });
        }

        /*
         * Закрытие по клику на оверлей — стандартный паттерн склада:
         * кладовщик может отменить действие, просто щёлкнув мимо окна.
         */
        dialog.addEventListener('click', (event) => {
            if (event.target === dialog) {
                dialog.hidden = true;
                activeForm = null;
            }
        });
    }

    function initialize() {
        initializeQrStep();
        initializeScanStep();
        initializeDeleteDialog();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialize);
    } else {
        initialize();
    }
})();