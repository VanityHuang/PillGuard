/* ============================================================
   PillGuard Web Frontend — API Client
   JWT management, fetch wrapper, 401 interception
   ============================================================ */

const PillGuardAPI = (() => {
    const API_BASE = '/pillguard/api';
    const TOKEN_KEY = 'pillguard_token';
    const USER_ID_KEY = 'pillguard_userId';

    /* --- Token management --- */
    function getToken() {
        return localStorage.getItem(TOKEN_KEY);
    }

    function getUserId() {
        return localStorage.getItem(USER_ID_KEY);
    }

    function saveAuth(token, userId) {
        localStorage.setItem(TOKEN_KEY, token);
        localStorage.setItem(USER_ID_KEY, userId);
    }

    function clearAuth() {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(USER_ID_KEY);
    }

    function isLoggedIn() {
        return !!getToken();
    }

    /* --- Core fetch wrapper --- */
    async function apiFetch(path, options = {}) {
        const url = `${API_BASE}${path}`;
        const headers = {
            'Content-Type': 'application/json',
            ...options.headers,
        };

        const token = getToken();
        if (token) {
            headers['Authorization'] = `Bearer ${token}`;
        }

        const config = {
            ...options,
            headers,
        };

        // Don't set Content-Type for FormData (multipart)
        if (options.body instanceof FormData) {
            delete headers['Content-Type'];
        }

        let response;
        try {
            response = await fetch(url, config);
        } catch (err) {
            // Network error
            throw new Error('无法连接到服务器，请检查网络');
        }

        // 401 → auto logout
        if (response.status === 401) {
            clearAuth();
            window.location.href = '/pillguard/login/';
            throw new Error('登录已过期，请重新登录');
        }

        return response;
    }

    /* --- Public API methods --- */

    /** POST /auth/login — returns { success, token, userId, message } */
    async function login(username, password) {
        const resp = await apiFetch('/auth/login', {
            method: 'POST',
            body: JSON.stringify({ username, password }),
        });

        const data = await resp.json();

        if (!resp.ok || !data.success) {
            throw new Error(data.message || '登录失败');
        }

        saveAuth(data.token, data.userId);
        return data;
    }

    /** GET /records?startDate=&endDate= — returns array of MedicationRecordDto */
    async function getRecords(startDate, endDate) {
        const params = new URLSearchParams();
        if (startDate) params.set('startDate', startDate);
        if (endDate) params.set('endDate', endDate);

        const resp = await apiFetch(`/records?${params.toString()}`);

        if (!resp.ok) {
            const data = await resp.json().catch(() => ({}));
            throw new Error(data.message || '获取记录失败');
        }

        return resp.json();
    }

    /** GET /health — returns { status, timestamp } */
    async function checkHealth() {
        try {
            const resp = await apiFetch('/health');
            const data = await resp.json();
            return data.status === 'ok';
        } catch {
            return false;
        }
    }

    /** Fetch a photo with auth header, return blob URL for <img> display */
    async function fetchPhotoBlobUrl(relativeUrl) {
        const url = `${API_BASE}${relativeUrl}`;
        const token = getToken();
        if (!token) throw new Error('未登录');

        const resp = await fetch(url, {
            headers: { 'Authorization': `Bearer ${token}` }
        });

        if (resp.status === 401) {
            clearAuth();
            window.location.href = '/pillguard/login/';
            throw new Error('登录已过期');
        }

        if (!resp.ok) {
            throw new Error(`加载图片失败 (${resp.status})`);
        }

        const blob = await resp.blob();
        return URL.createObjectURL(blob);
    }

    /** POST /test/email — send a test email, returns { success, message } */
    async function sendTestEmail() {
        const resp = await apiFetch('/test/email', { method: 'POST' });
        const data = await resp.json();
        if (!resp.ok || !data.success) {
            throw new Error(data.message || '发送失败');
        }
        return data;
    }

    /** Revoke a blob URL to free memory */
    function revokeBlobUrl(blobUrl) {
        URL.revokeObjectURL(blobUrl);
    }

    /** Logout — clear auth and redirect */
    function logout() {
        clearAuth();
        window.location.href = '/pillguard/login/';
    }

    /* --- Public API --- */
    return {
        getToken,
        getUserId,
        isLoggedIn,
        login,
        getRecords,
        checkHealth,
        sendTestEmail,
        fetchPhotoBlobUrl,
        revokeBlobUrl,
        logout,
    };
})();
