(() => {
    'use strict';

    /*
     * Модуль администрирования использует собственные селекторы
     * (adminDateTime, adminSidebar), чтобы скрипты разделов не мешали
     * друг другу при случайной склейке разметки.
     */
    function initializeInterface() {
        const dateTimeElement = document.getElementById('adminDateTime');
        const mainElement = document.querySelector('.main');
        const sidebar = document.getElementById('adminSidebar');

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
         * Меню раскрывается по :focus-within; щелчок по рабочей области
         * снимает фокус, чтобы свёрнутое состояние возвращалось без
         * необходимости наводить курсор куда-то специально.
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

        updateDateTime();
        window.setInterval(updateDateTime, 1000);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeInterface);
    } else {
        initializeInterface();
    }
})();
