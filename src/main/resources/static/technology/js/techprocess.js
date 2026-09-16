(() => {
    'use strict';

    /*
     * Модуль страниц техпроцессов:
     *  1) конструктор маршрута (строки операций, нормы, зависимости);
     *  2) каскадный выбор изделия классификатора;
     *  3) подтверждение удаления.
     *
     * ВАЖНО: весь человекочитаемый текст (кириллица) берётся из
     * data-атрибутов HTML, а не из литералов этого файла.
     * Статика отдаётся без charset, поэтому кириллица прямо в JS
     * отображалась бы «кракозябрами».
     */

    const form = document.querySelector('[data-tp-form]');
    const rowsBox = document.querySelector('[data-tp-rows]');
    const rowTemplate = document.querySelector('[data-tp-row-template]');
    const addButton = document.querySelector('[data-tp-add-row]');
    const dependencyTemplate =
        document.querySelector('[data-tp-dep-template]');
    const workplaceRequiredTemplate =
        document.querySelector('[data-tp-workplace-required-message]');
    const workplaceTemplate =
        document.querySelector('[data-tp-workplace-template]');

    const operationNames = new Map();

    function collectOperationNames() {
        document
            .querySelectorAll('[data-operation-catalog] option')
            .forEach(option => {
                if (option.value) {
                    operationNames.set(
                        option.value,
                        option.dataset.name || option.textContent.trim()
                    );
                }
            });
    }

    const rows = () =>
        [...rowsBox.children].filter(element =>
            element.classList.contains('tp-row')
        );

    function operationSelect(row) {
        return row.querySelector('[data-tp-operation]');
    }

    function normFields(row) {
        return [
            row.querySelector('[data-tp-labor]'),
            row.querySelector('[data-tp-machine]'),
            row.querySelector('[data-tp-limit]')
        ].filter(Boolean);
    }

    function syncRow(row) {
        const select = operationSelect(row);
        const enabled = Boolean(select.value);

        /*
         * Сам select операции остаётся активным всегда, иначе
         * пустую строку нельзя было бы заполнить. Отключаем
         * только поля норм.
         */
        normFields(row).forEach(field => {
            field.disabled = !enabled;
            field.required = enabled;
        });

        row.querySelector('[data-tp-deps]')?.toggleAttribute(
            'hidden',
            !enabled
        );

        /*
         * Рабочее место и описание относятся к конкретной операции,
         * поэтому скрыты, пока операция не выбрана.
         */
        row.querySelector('.tp-row-places')?.toggleAttribute(
            'hidden',
            !enabled
        );

        row.querySelector('.tp-row-note')?.toggleAttribute(
            'hidden',
            !enabled
        );

        row.querySelector('[data-tp-remove]')?.toggleAttribute(
            'hidden',
            !enabled
        );

        row.classList.toggle('is-empty', !enabled);
    }

    function depsEmptyText() {
        /*
         * Текст берётся из <template data-tp-dep-template> —
         * он лежит в HTML и декодируется корректно.
         */
        return dependencyTemplate
            ? dependencyTemplate.textContent.trim()
            : '';
    }

    function rebuildDependencies() {
        const current = rows();
        const selected = new Map();

        current.forEach(row => {
            const value = operationSelect(row).value;

            if (value) {
                selected.set(row, value);
            }
        });

        current.forEach(row => {
            const box = row.querySelector('[data-tp-deps]');

            if (!box) {
                return;
            }

            const own = selected.get(row);

            const checked = new Set(
                [...box.querySelectorAll('input:checked')]
                    .map(input => input.value)
            );

            box.replaceChildren();

            const candidates = [...selected.entries()]
                .filter(([, value]) => value !== own);

            if (candidates.length === 0) {
                const hint = document.createElement('span');

                hint.className = 'tp-deps-empty';
                hint.textContent = depsEmptyText();

                box.append(hint);
                return;
            }

            candidates.forEach(([, value]) => {
                const label = document.createElement('label');

                label.className = 'tp-dep-check';

                const input = document.createElement('input');

                input.type = 'checkbox';
                input.value = value;
                input.checked = checked.has(value);
                input.dataset.dep = '1';

                label.append(
                    input,
                    document.createTextNode(
                        operationNames.get(value) || value
                    )
                );

                box.append(label);
            });
        });
    }

    function cleanupDependencies() {
        const alive = new Set(
            rows()
                .map(row => operationSelect(row).value)
                .filter(Boolean)
        );

        rows().forEach(row => {
            row.querySelectorAll('[data-tp-deps] input').forEach(input => {
                if (!alive.has(input.value)) {
                    input.checked = false;
                }
            });
        });
    }

    function renumber() {
        let index = 0;

        rows().forEach(row => {
            const select = operationSelect(row);

            [...[operationSelect(row)], ...normFields(row)]
                .flat()
                .forEach(field => {
                    if (!select.value) {
                        field.removeAttribute('name');
                        return;
                    }

                    const property = field.dataset.tpField;

                    field.name = `operations[${index}].${property}`;
                });

            /*
             * Поле описания операции в маршруте.
             */
            const noteField = row.querySelector(
                '.tp-row-note [data-tp-field="note"]'
            );

            if (noteField) {
                noteField.name = select.value
                    ? `operations[${index}].note`
                    : '';

                if (!select.value) {
                    noteField.removeAttribute('name');
                }
            }

            /*
             * Work places: every filled select is submitted as a
             * separate value of the same list property. The trailing
             * empty select has no name, so it is not submitted.
             */
            row.querySelectorAll('[data-tp-workplace]')
                .forEach(placeSelect => {
                    if (!select.value || !placeSelect.value) {
                        placeSelect.removeAttribute('name');
                    } else {
                        placeSelect.name =
                            `operations[${index}].workPlaceIds`;
                    }
                });

            row.querySelectorAll('[data-tp-deps] input').forEach(input => {
                if (!select.value) {
                    input.removeAttribute('name');
                    return;
                }

                input.name =
                    `operations[${index}].predecessorOperationIds`;
            });

            const badge = row.querySelector('[data-tp-row-number]');

            if (badge) {
                badge.textContent = String(index + 1);
            }

            if (select.value) {
                index++;
            }
        });
    }

    function bindRow(row) {
        if (row.dataset.bound === '1') {
            return;
        }

        row.dataset.bound = '1';

        operationSelect(row).addEventListener('change', normalize);

        row.querySelector('[data-tp-remove]').addEventListener(
            'click',
            () => {
                row.remove();
                normalize();
            }
        );

        row.querySelectorAll('[data-tp-deps]').forEach(box => {
            box.addEventListener('change', renumber);
        });
    }

    /*
     * Work places are added one row at a time: as soon as the last
     * select gets a value, an empty row appears below it, so the
     * next place can be chosen right away.
     */
    function workplaceSelects(row) {
        return [...row.querySelectorAll('[data-tp-workplace]')];
    }

    function ensureWorkplaceRow(row) {
        const box = row.querySelector('[data-tp-workplaces]');

        if (!box || !workplaceTemplate || workplaceSelects(row).length === 0) {
            return;
        }

        const selects = workplaceSelects(row);
        const last = selects[selects.length - 1];

        if (last.value) {
            box.append(workplaceTemplate.content.cloneNode(true));
        }
    }

    function handleWorkplaceChange(row) {
        ensureWorkplaceRow(row);
        renumber();
    }

    function handleWorkplaceRemove(row, button) {
        const placeRow = button.closest('.tp-workplace-row');

        if (!placeRow) {
            return;
        }

        placeRow.remove();

        /* An operation always needs at least one place to pick. */
        if (workplaceSelects(row).length === 0 && workplaceTemplate) {
            row.querySelector('[data-tp-workplaces]')
                ?.append(workplaceTemplate.content.cloneNode(true));
        }

        renumber();
    }

    function addRow() {
        rowsBox.append(rowTemplate.content.cloneNode(true));

        const row = rowsBox.lastElementChild;

        bindRow(row);
        syncRow(row);

        return row;
    }

    function normalize() {
        rows().forEach(bindRow);
        rows().forEach(syncRow);
        cleanupDependencies();

        rows()
            .filter(row => !operationSelect(row).value)
            .slice(1)
            .forEach(row => row.remove());

        if (!rows().some(row => !operationSelect(row).value)) {
            addRow();
        }

        /*
         * Rows loaded from the server already have places chosen,
         * so an empty row is appended right away for the next one.
         */
        rows().forEach(ensureWorkplaceRow);

        rebuildDependencies();
        renumber();
    }

    /*
     * Проверка перед отправкой: у каждой заполненной операции
     * должно быть выбрано рабочее место.
     * Текст сообщения берётся из <template> в HTML.
     */
    function validateWorkPlaces() {
        const missing = rows().some(row => {
            if (!operationSelect(row).value) {
                return false;
            }

            return !workplaceSelects(row).some(select => select.value);
        });

        if (!missing) {
            return true;
        }

        window.alert(
            workplaceRequiredTemplate
                ? workplaceRequiredTemplate.textContent.trim()
                : ''
        );

        return false;
    }

    function initBuilder() {
        if (!form || !rowsBox || !rowTemplate) {
            return;
        }

        collectOperationNames();
        normalize();

        /*
         * Work place selects are created on the fly, so their
         * events are handled through delegation on the rows box.
         */
        rowsBox.addEventListener('change', event => {
            const select = event.target.closest('[data-tp-workplace]');

            if (!select) {
                return;
            }

            const row = select.closest('.tp-row');

            if (row) {
                handleWorkplaceChange(row);
            }
        });

        rowsBox.addEventListener('click', event => {
            const button = event.target.closest(
                '[data-tp-workplace-remove]'
            );

            if (!button) {
                return;
            }

            const row = button.closest('.tp-row');

            if (row) {
                handleWorkplaceRemove(row, button);
            }
        });

        addButton?.addEventListener('click', () => {
            addRow();
            normalize();
        });

        form.addEventListener('submit', event => {
            renumber();

            if (!validateWorkPlaces()) {
                event.preventDefault();
            }
        });
    }

    /*
     * Каскадный выбор изделия классификатора:
     * вид изделия -> класс датчика -> конкретное изделие.
     * Подписи пунктов берутся из data-атрибутов источника.
     */
    function initProductPicker() {
        const dataSource =
            document.querySelector('[data-tp-classifier-data]');
        const typePicker =
            document.querySelector('[data-tp-type-picker]');
        const sensorField =
            document.querySelector('[data-tp-sensor-field]');
        const sensorPicker =
            document.querySelector('[data-tp-sensor-picker]');
        const classifierPicker =
            document.querySelector('[data-tp-classifier-picker]');

        if (!dataSource || !typePicker || !classifierPicker) {
            return;
        }

        const texts = dataSource.dataset || {};

        const items = [...dataSource.querySelectorAll('li')].map(item => ({
            id: item.dataset.id,
            code: item.dataset.code,
            type: item.dataset.type,
            sensorClassId: item.dataset.sensorClassId || '',
            sensorClassName: item.dataset.sensorClassName || '',
            name: item.dataset.name || ''
        }));

        const targetInput =
            document.querySelector('[data-tp-target-input]');
        const continueButton =
            document.querySelector('[data-tp-continue]');

        const option = (value, text) => {
            const element = document.createElement('option');

            element.value = value;
            element.textContent = text;

            return element;
        };

        const separator = ' ' + (texts.separator || '') + ' ';

        const label = item => {
            const parts = [];

            if (texts.codePrefix) {
                parts.push(texts.codePrefix + ' ' + item.code);
            } else {
                parts.push(String(item.code));
            }

            if (item.sensorClassName) {
                parts.push(item.sensorClassName);
            }

            if (item.name) {
                parts.push(item.name);
            }

            return parts.join(separator);
        };

        const matches = item => {
            if (typePicker.value && item.type !== typePicker.value) {
                return false;
            }

            if (sensorPicker
                && sensorPicker.value
                && item.sensorClassId !== sensorPicker.value) {
                return false;
            }

            return true;
        };

        const isSensorType = () => typePicker.value === 'SENSOR';

        const syncSensorVisibility = () => {
            if (!sensorField || !sensorPicker) {
                return;
            }

            sensorField.hidden = !isSensorType();

            if (!isSensorType()) {
                sensorPicker.value = '';
            }
        };

        const rebuildSensors = () => {
            if (!sensorPicker) {
                return;
            }

            const previous = sensorPicker.value;

            sensorPicker.replaceChildren(
                option('', texts.sensorClassLabel || '')
            );

            const seen = new Set();

            items
                .filter(item =>
                    item.type === 'SENSOR'
                    && item.sensorClassId
                    && (!typePicker.value
                        || typePicker.value === 'SENSOR'))
                .forEach(item => {
                    if (seen.has(item.sensorClassId)) {
                        return;
                    }

                    seen.add(item.sensorClassId);
                    sensorPicker.append(
                        option(item.sensorClassId, item.sensorClassName)
                    );
                });

            if ([...sensorPicker.options]
                    .some(o => o.value === previous)) {
                sensorPicker.value = previous;
            }
        };

        const rebuildClassifiers = () => {
            const previous = classifierPicker.value;

            classifierPicker.replaceChildren(
                option('', texts.classifierLabel || '')
            );

            items.filter(matches).forEach(item => {
                classifierPicker.append(option(item.id, label(item)));
            });

            if ([...classifierPicker.options]
                    .some(o => o.value === previous)) {
                classifierPicker.value = previous;
            }
        };

        const syncContinue = () => {
            if (targetInput) {
                targetInput.value = classifierPicker.value;
            }

            if (continueButton) {
                continueButton.disabled = !classifierPicker.value;
            }
        };

        const refresh = () => {
            syncSensorVisibility();
            rebuildSensors();
            rebuildClassifiers();
            syncContinue();
        };

        typePicker.addEventListener('change', refresh);
        sensorPicker?.addEventListener('change', () => {
            rebuildClassifiers();
            syncContinue();
        });
        classifierPicker.addEventListener('change', syncContinue);

        continueButton?.addEventListener('click', event => {
            if (!classifierPicker.value) {
                return;
            }

            /*
             * Страница копирования: скрытое поле уже содержит изделие,
             * кнопка — submit формы, редирект не требуется.
             */
            if (targetInput) {
                return;
            }

            event.preventDefault();

            const base = window.TP_CREATE_NEW_URL
                || '/technology/techprocess/create/new';

            location.assign(
                base + '?classifierId='
                + encodeURIComponent(classifierPicker.value)
            );
        });

        refresh();
    }

    function initDelete() {
        const deleteForm =
            document.querySelector('[data-tp-delete-form]');

        deleteForm?.addEventListener('submit', event => {
            const message = deleteForm.dataset.confirm || '';

            if (!window.confirm(message)) {
                event.preventDefault();
            }
        });
    }

    /*
     * Graph arrows of the route.
     *
     * Lines are orthogonal (right angles only), like on a routing
     * diagram. Every node reserves separate slots on its right and
     * left edge for its outgoing and incoming links, so several
     * arrows never overlap and never cross a node body: a shared
     * vertical channel is placed in the gap between two columns.
     *
     * Edges come from the data-edges attribute ("0-1,1-2");
     * coordinates are measured from the rendered nodes.
     */
    function initGraphLinks() {
        const graph = document.querySelector('[data-tp-graph]');
        const svg = document.querySelector('[data-tp-graph-links]');

        if (!graph || !svg) {
            return;
        }

        const SVG_NS = 'http://www.w3.org/2000/svg';
        const SLOT_STEP = 11;
        const CHANNEL_SHIFT = 14;

        const draw = () => {
            const box = graph.getBoundingClientRect();

            svg.setAttribute('viewBox',
                '0 0 ' + box.width + ' ' + box.height);

            svg.querySelectorAll('path.tp-link')
                .forEach(path => path.remove());

            const edges = (svg.dataset.edges || '')
                .split(',')
                .filter(Boolean)
                .map(pair => pair.split('-').map(Number))
                .filter(pair => pair.length === 2);

            const shapeOf = (id) => graph.querySelector(
                '[data-node-id="' + id + '"] .tp-node-shape'
            );

            const boxes = new Map();

            graph.querySelectorAll('.tp-node').forEach(node => {
                const shape = node.querySelector('.tp-node-shape');

                if (shape) {
                    boxes.set(node.dataset.nodeId,
                        shape.getBoundingClientRect());
                }
            });

            /*
             * Columns of the graph, needed to place the vertical
             * part of a line into the empty gap between columns.
             */
            const columns = [];

            boxes.forEach(rect => columns.push(rect));

            const leftOf = (rect) =>
                columns
                    .filter(other => other.left > rect.right + 1)
                    .reduce(
                        (min, other) => Math.min(min, other.left),
                        Infinity
                    );

            /*
             * Sort links of every node by the vertical position of
             * the opposite end: outgoing slots then fan out in the
             * same order as the targets, which removes crossings
             * near the node.
             */
            const outgoing = new Map();
            const incoming = new Map();

            const push = (map, key, value) => {
                if (!map.has(key)) {
                    map.set(key, []);
                }

                map.get(key).push(value);
            };

            edges.forEach(pair => {
                const from = boxes.get(String(pair[0]));
                const to = boxes.get(String(pair[1]));

                if (!from || !to) {
                    return;
                }

                push(outgoing, pair[0], { to: pair[1], rect: to });
                push(incoming, pair[1], { from: pair[0], rect: from });
            });

            const slotIndex = (map, key, targetId, ownId) => {
                const list = map.get(key) || [];

                list.sort((a, b) =>
                    (a.rect.top + a.rect.height / 2)
                    - (b.rect.top + b.rect.height / 2));

                for (let i = 0; i < list.length; i++) {
                    const id = map === outgoing ? list[i].to : list[i].from;

                    if (id === targetId) {
                        return i;
                    }
                }

                return 0;
            };

            const slotY = (rect, index, total) => {
                const center = rect.top + rect.height / 2 - box.top;
                const offset = (index - (total - 1) / 2) * SLOT_STEP;

                return center + offset;
            };

            /*
             * Horizontal segment must not cut through a node: when a
             * link jumps over a column, the crossing node is found
             * and the line is moved to the closest free corridor
             * between two rows.
             */
            const freeY = (xa, xb, y) => {
                const blockers = [];

                boxes.forEach(rect => {
                    const left = rect.left - box.left;
                    const right = rect.right - box.left;
                    const top = rect.top - box.top;
                    const bottom = rect.bottom - box.top;

                    const overlapsX = left < xb - 2 && right > xa + 2;
                    const overlapsY = y > top - 4 && y < bottom + 4;

                    if (overlapsX && overlapsY) {
                        blockers.push({ top: top, bottom: bottom });
                    }
                });

                if (blockers.length === 0) {
                    return y;
                }

                const candidates = [];

                blockers.forEach(blocker => {
                    candidates.push(blocker.top - 9);
                    candidates.push(blocker.bottom + 9);
                });

                candidates.sort((a, b) => Math.abs(a - y) - Math.abs(b - y));

                for (const candidate of candidates) {
                    const stillBlocked = boxes.some(rect => {
                        const left = rect.left - box.left;
                        const right = rect.right - box.left;
                        const top = rect.top - box.top;
                        const bottom = rect.bottom - box.top;

                        return left < xb - 2
                            && right > xa + 2
                            && candidate > top - 4
                            && candidate < bottom + 4;
                    });

                    if (!stillBlocked) {
                        return candidate;
                    }
                }

                return y;
            };

            edges.forEach(pair => {
                const from = boxes.get(String(pair[0]));
                const to = boxes.get(String(pair[1]));

                if (!from || !to) {
                    return;
                }

                const outList = outgoing.get(pair[0]) || [];
                const inList = incoming.get(pair[1]) || [];

                const outIndex = slotIndex(outgoing, pair[0], pair[1]);
                const inIndex = slotIndex(incoming, pair[1], pair[0]);

                const x1 = from.right - box.left;
                const x2 = to.left - box.left;

                const y1 = slotY(from, outIndex, outList.length);
                const y2 = slotY(to, inIndex, inList.length);

                /*
                 * Vertical channel: middle of the gap between the
                 * two columns. Several arrows of the same pair of
                 * columns share the channel but keep different
                 * heights, so the drawing stays readable.
                 */
                const nextColumnLeft = leftOf(from);

                const channel = Number.isFinite(nextColumnLeft)
                    ? (from.right - box.left
                        + Math.min(nextColumnLeft, to.right) - box.left) / 2
                    : x1 + CHANNEL_SHIFT;

                const channelX = Math.min(
                    Math.max(channel, x1 + 12),
                    x2 - 12
                );

                /*
                 * A link that skips a column needs two vertical
                 * segments, each placed in its own gap, so no part
                 * of the arrow runs over a node.
                 */
                const spansColumn = columns.some(rect => {
                    const left = rect.left - box.left;
                    const right = rect.right - box.left;

                    return left > x1 + 2 && right < x2 - 2;
                });

                const path = document.createElementNS(SVG_NS, 'path');

                path.setAttribute('class', 'tp-link');

                if (Math.abs(y1 - y2) < 1.5) {
                    const straight = freeY(x1, x2, y1);

                    path.setAttribute('d',
                        'M ' + x1 + ' ' + y1
                        + ' H ' + (x2 - 2)
                    );

                    if (Math.abs(straight - y1) > 0.5) {
                        path.setAttribute('d',
                            'M ' + x1 + ' ' + y1
                            + ' H ' + channelX
                            + ' V ' + straight
                            + ' H ' + (x2 - 2)
                        );
                    }
                } else if (spansColumn) {
                    const firstChannel = x1 + 16;
                    const secondChannel = x2 - 16;

                    const midY = freeY(
                        firstChannel,
                        secondChannel,
                        (y1 + y2) / 2
                    );

                    path.setAttribute('d',
                        'M ' + x1 + ' ' + y1
                        + ' H ' + firstChannel
                        + ' V ' + midY
                        + ' H ' + secondChannel
                        + ' V ' + y2
                        + ' H ' + (x2 - 2)
                    );
                } else {
                    const midY1 = freeY(x1, channelX, y1);
                    const midY2 = freeY(channelX, x2, y2);

                    path.setAttribute('d',
                        'M ' + x1 + ' ' + y1
                        + ' H ' + channelX
                        + (Math.abs(midY1 - y1) > 0.5 ? ' V ' + midY1 : '')
                        + ' V ' + midY2
                        + ' H ' + (x2 - 2)
                    );
                }

                path.setAttribute(
                    'marker-end',
                    'url(#tp-arrow-head)'
                );

                svg.append(path);
            });
        };

        const redrawLater = () => {
            window.requestAnimationFrame(draw);
        };

        redrawLater();

        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(draw);
        }

        window.addEventListener('resize', redrawLater);
    }

    function init() {
        initBuilder();
        initProductPicker();
        initDelete();
        initGraphLinks();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init, { once: true });
    } else {
        init();
    }
})();