/* ============================================================
   PillGuard Web Frontend — Calendar Component
   Month view with morning/evening status dots, detail panel, filters
   ============================================================ */

const PillGuardCalendar = (() => {
    /* --- State --- */
    let currentYear, currentMonth;
    let records = [];                    // raw API response
    let recordMap = {};                  // { "YYYY-MM-DD": { morning: record|null, evening: record|null } }
    let selectedDate = null;
    let filterTimeSlot = 'all';          // 'all' | 'morning' | 'evening'
    let filterStatus = 'all';            // 'all' | 'normal' | 'duplicate' | 'missed'
    let dataCallbacks = [];              // called after records are loaded

    /* --- Constants --- */
    const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六'];

    /* --- Helpers --- */
    function formatDate(y, m, d) {
        const mm = String(m).padStart(2, '0');
        const dd = String(d).padStart(2, '0');
        return `${y}-${mm}-${dd}`;
    }

    function todayStr() {
        const t = new Date();
        return formatDate(t.getFullYear(), t.getMonth() + 1, t.getDate());
    }

    function isFutureDate(dateStr) {
        return dateStr > todayStr();
    }

    function parseTakenAt(isoStr) {
        if (!isoStr) return null;
        try {
            const d = new Date(isoStr);
            if (isNaN(d.getTime())) return null;
            return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
        } catch {
            return null;
        }
    }

    function getRecordStatus(rec) {
        if (!rec || !rec.completed) return 'missed';
        return rec.isDuplicate ? 'duplicate' : 'normal';
    }

    function getStatusClass(status) {
        return status; // 'normal', 'duplicate', 'missed'
    }

    function getStatusLabel(status) {
        const labels = { normal: '正常打卡', duplicate: '重复打卡', missed: '未打卡' };
        return labels[status] || '';
    }

    /* --- Data --- */
    function buildRecordMap() {
        recordMap = {};
        for (const rec of records) {
            const date = rec.date;
            if (!recordMap[date]) {
                recordMap[date] = { morning: null, evening: null };
            }
            if (rec.timeSlot === 'morning') {
                recordMap[date].morning = rec;
            } else if (rec.timeSlot === 'evening') {
                recordMap[date].evening = rec;
            }
        }
    }

    function getDateRecords(dateStr) {
        return recordMap[dateStr] || { morning: null, evening: null };
    }

    /* --- Rendering --- */
    function renderMonthLabel() {
        const el = document.getElementById('month-label');
        if (el) {
            el.textContent = `${currentYear}年 ${currentMonth}月`;
        }
    }

    function cellMatchesFilter(dateStr) {
        const dayRecs = getDateRecords(dateStr);

        if (filterStatus !== 'all') {
            const mStatus = getRecordStatus(dayRecs.morning);
            const eStatus = getRecordStatus(dayRecs.evening);
            const matchMorning = (filterStatus === 'normal' || filterStatus === 'duplicate')
                ? mStatus === filterStatus
                : (mStatus === 'missed');
            const matchEvening = (filterStatus === 'normal' || filterStatus === 'duplicate')
                ? eStatus === filterStatus
                : (eStatus === 'missed');
            if (!matchMorning && !matchEvening) return false;
        }

        if (filterTimeSlot !== 'all') {
            const rec = dayRecs[filterTimeSlot];
            const status = getRecordStatus(rec);
            if (filterStatus !== 'all') {
                const match = (filterStatus === 'normal' || filterStatus === 'duplicate')
                    ? status === filterStatus
                    : (status === 'missed');
                if (!match) return false;
            }
        }

        return true;
    }

    function renderDot(slot, status, dateStr) {
        // Determine visibility based on filters
        let visible = true;
        if (filterTimeSlot !== 'all' && filterTimeSlot !== slot) {
            visible = false;
        }
        if (filterStatus !== 'all') {
            if (!(filterStatus === status)) {
                visible = false;
            }
        }

        if (!visible) {
            return `<span class="dot no-data" style="opacity:0.3"></span>`;
        }

        return `<span class="dot ${getStatusClass(status)}" title="${slot === 'morning' ? '早' : '晚'}: ${getStatusLabel(status)}"></span>`;
    }

    function renderCalendarGrid() {
        const grid = document.getElementById('calendar-grid');
        if (!grid) return;

        const firstDay = new Date(currentYear, currentMonth - 1, 1);
        const lastDay = new Date(currentYear, currentMonth, 0);
        const daysInMonth = lastDay.getDate();
        const startDow = firstDay.getDay(); // 0=Sun

        let html = '';

        // Empty cells before first day
        for (let i = 0; i < startDow; i++) {
            html += '<div class="calendar-cell empty"></div>';
        }

        // Day cells
        for (let d = 1; d <= daysInMonth; d++) {
            const dateStr = formatDate(currentYear, currentMonth, d);
            const dayRecs = getDateRecords(dateStr);

            let cellClass = 'calendar-cell';
            if (dateStr === todayStr()) cellClass += ' today';
            if (dateStr === selectedDate) cellClass += ' selected';

            // Future dates: show only date number, no dots, not clickable
            if (isFutureDate(dateStr)) {
                cellClass += ' future';
                html += `
                    <div class="${cellClass}" data-date="${dateStr}">
                        <span class="date-num">${d}</span>
                    </div>`;
                continue;
            }

            const mStatus = getRecordStatus(dayRecs.morning);
            const eStatus = getRecordStatus(dayRecs.evening);

            const morningDot = renderDot('morning', mStatus, dateStr);
            const eveningDot = renderDot('evening', eStatus, dateStr);

            html += `
                <div class="${cellClass}" data-date="${dateStr}" onclick="PillGuardCalendar.onDayClick('${dateStr}')">
                    <span class="date-num">${d}</span>
                    <div class="dots-row">
                        ${morningDot}
                        ${eveningDot}
                    </div>
                </div>`;
        }

        grid.innerHTML = html;
    }

    function renderDetailPanel() {
        const panel = document.getElementById('detail-panel');
        const dateTitle = document.getElementById('detail-date');
        const morningSlot = document.getElementById('detail-morning');
        const eveningSlot = document.getElementById('detail-evening');
        if (!panel || !dateTitle) return;

        if (!selectedDate) {
            panel.classList.remove('show');
            return;
        }

        const dayRecs = getDateRecords(selectedDate);
        const dateObj = new Date(selectedDate + 'T00:00:00');
        const weekday = WEEKDAYS[dateObj.getDay()];

        dateTitle.textContent = `${selectedDate} 星期${weekday}`;

        // Morning slot
        if (morningSlot) {
            const mRec = dayRecs.morning;
            const mStatus = getRecordStatus(mRec);
            const mTime = parseTakenAt(mRec ? mRec.takenAt : null);
            morningSlot.innerHTML = `
                <span class="slot-icon">🌅</span>
                <div class="slot-info">
                    <div class="slot-label">早上 (2:00 – 14:00)</div>
                    <div class="slot-time">${mTime || '—'}</div>
                </div>
                <span class="slot-status ${getStatusClass(mStatus)}">${getStatusLabel(mStatus)}</span>`;
        }

        // Evening slot
        if (eveningSlot) {
            const eRec = dayRecs.evening;
            const eStatus = getRecordStatus(eRec);
            const eTime = parseTakenAt(eRec ? eRec.takenAt : null);
            eveningSlot.innerHTML = `
                <span class="slot-icon">🌙</span>
                <div class="slot-info">
                    <div class="slot-label">晚上 (14:00 – 次日 2:00)</div>
                    <div class="slot-time">${eTime || '—'}</div>
                </div>
                <span class="slot-status ${getStatusClass(eStatus)}">${getStatusLabel(eStatus)}</span>`;
        }

        panel.classList.add('show');
    }

    function renderAll() {
        renderMonthLabel();
        renderCalendarGrid();
        renderDetailPanel();
    }

    /* --- Data Loading --- */
    function showLoading(show) {
        let overlay = document.getElementById('loading-overlay');
        if (show) {
            if (!overlay) {
                overlay = document.createElement('div');
                overlay.id = 'loading-overlay';
                overlay.className = 'loading-overlay';
                overlay.innerHTML = '<div class="spinner spinner-dark"></div>';
                document.body.appendChild(overlay);
            }
            overlay.style.display = 'flex';
        } else {
            if (overlay) {
                overlay.style.display = 'none';
            }
        }
    }

    function showToast(message, type) {
        // Remove existing toasts
        document.querySelectorAll('.toast').forEach(t => t.remove());

        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        toast.textContent = message;
        document.body.appendChild(toast);

        setTimeout(() => {
            toast.remove();
        }, 3000);
    }

    async function loadRecords() {
        showLoading(true);

        const startDate = formatDate(currentYear, currentMonth, 1);
        const lastDay = new Date(currentYear, currentMonth, 0).getDate();
        const endDate = formatDate(currentYear, currentMonth, lastDay);

        try {
            records = await PillGuardAPI.getRecords(startDate, endDate);
            buildRecordMap();
            renderAll();
            // Notify listeners
            dataCallbacks.forEach(cb => cb(records, recordMap));
        } catch (err) {
            showToast(err.message, 'error');
            records = [];
            recordMap = {};
            renderAll();
        } finally {
            showLoading(false);
        }
    }

    /* --- Navigation --- */
    function navigateMonth(delta) {
        currentMonth += delta;
        if (currentMonth > 12) {
            currentMonth = 1;
            currentYear++;
        } else if (currentMonth < 1) {
            currentMonth = 12;
            currentYear--;
        }
        selectedDate = null;
        loadRecords();
    }

    function goToToday() {
        const today = new Date();
        currentYear = today.getFullYear();
        currentMonth = today.getMonth() + 1;
        selectedDate = formatDate(currentYear, currentMonth, today.getDate());
        loadRecords();
    }

    /* --- Day Click --- */
    function onDayClick(dateStr) {
        // Ignore future dates
        if (isFutureDate(dateStr)) return;

        if (selectedDate === dateStr) {
            // Deselect
            selectedDate = null;
        } else {
            selectedDate = dateStr;
        }
        renderAll();
    }

    /* --- Filters --- */
    function setFilterTimeSlot(value) {
        filterTimeSlot = value;
        renderAll();
    }

    function setFilterStatus(value) {
        filterStatus = value;
        renderAll();
    }

    /* --- Init --- */
    function init() {
        // Parse URL params ?year=2026&month=6
        const params = new URLSearchParams(window.location.search);
        const today = new Date();
        currentYear = parseInt(params.get('year')) || today.getFullYear();
        currentMonth = parseInt(params.get('month')) || (today.getMonth() + 1);

        // Clamp
        if (currentMonth < 1 || currentMonth > 12) {
            currentYear = today.getFullYear();
            currentMonth = today.getMonth() + 1;
        }

        loadRecords();
    }

    function closeDetail() {
        selectedDate = null;
        renderAll();
    }

    function getRecordsData() {
        return { records, recordMap };
    }

    function onRecordsUpdated(cb) {
        dataCallbacks.push(cb);
    }

    /* --- Public API --- */
    return {
        init,
        navigateMonth,
        goToToday,
        onDayClick,
        closeDetail,
        setFilterTimeSlot,
        setFilterStatus,
        getRecordsData,
        onRecordsUpdated,
    };
})();
