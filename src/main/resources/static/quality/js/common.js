(() => {
    'use strict';

    /*
     * Часы и поведение бокового меню. Селекторы и ID собственные
     * (sk*), иначе скрипты склада, цеха, диспетчерской и бюро
     * входного контроля перехватывали бы разметку друг друга при
     * случайной склейке страниц.
     */
    function initializeInterface() {
        const dateTimeElement = document.getElementById('skDateTime');
        const mainElement = document.querySelector('.main');
        const sidebar = document.getElementById('skSidebar');

        function updateDateTime() {
            if (!dateTimeElement) {
                return;
            }

            const now = new Date();

            dateTimeElement.dateTime = now.toISOString();

            dateTimeElement.textContent =
                new Intl.DateTimeFormat('ru-RU', {
                    day: '2-digit',
                    month: '2-digit',
                    year: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                    second: '2-digit'
                }).format(now);
        }

        /*
         * Меню раскрывается по :focus-within; щелчок по рабочей
         * области снимает фокус, чтобы свёрнутое состояние возвращалось
         * без необходимости наводить курсор куда-то специально.
         */
        if (mainElement && sidebar) {
            mainElement.addEventListener('pointerdown', () => {
                const activeElement = document.activeElement;

                if (
                    sidebar.contains(activeElement) &&
                    typeof activeElement.blur === 'function'
                ) {
                    activeElement.blur();
                }
            });
        }

        /*
         * Отмена заказа необратима для состава: ячейки вернутся в
         * изолятор, и историю ремонта восстановишь только новым ЗНП.
         * Поэтому отказ подтверждают, а не принимают молча — текст
         * держат в атрибуте формы, чтобы он читался рядом с кнопкой.
         */
        document.querySelectorAll('form[data-sk-confirm]').forEach((form) => {
            form.addEventListener('submit', (event) => {
                const message = form.dataset.skConfirm;

                if (message && !window.confirm(message)) {
                    event.preventDefault();
                }
            });
        });

        updateDateTime();
        window.setInterval(updateDateTime, 1000);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeInterface);
    } else {
        initializeInterface();
    }
})();
