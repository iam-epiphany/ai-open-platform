/**
 * api.js —— 后端接口封装
 * 全部接口经 nginx /api 前缀转发到 8081，返回 Promise<Result>
 */
var api = {
    // ===== 用户 =====
    sendCode: function (phone) {
        return http.post('/user/code', null, { params: { phone: phone } });
    },
    login: function (phone, code) {
        return http.post('/user/login', { phone: phone, code: code });
    },
    adminLogin: function (username, password) {
        return http.post('/admin/login', { username: username, password: password });
    },
    me: function () {
        return http.get('/user/me');
    },
    logout: function () {
        return http.post('/user/logout');
    },

    // ===== 活动 =====
    listActivities: function () {
        return http.get('/credit-activities/list');
    },
    getActivity: function (id) {
        return http.get('/credit-activities/' + id);
    },

    // ===== 抢购 =====
    grant: function (skuId) {
        return http.post('/credit-orders/claim/' + skuId);
    },

    // ===== 订单 / 权益 =====
    myOrders: function () {
        return http.get('/credit-orders/user');
    },

    // ===== Credits =====
    creditAccount: function () {
        return http.get('/credits/account');
    },
    creditSummary: function () {
        return http.get('/credits/summary');
    },
    creditRecords: function (type, current, size) {
        return http.get('/credits/records', { params: { type: type || '', current: current || 1, size: size || 10 } });
    },
    creditDaily: function (days) {
        return http.get('/credits/daily', { params: { days: days || 7 } });
    },
    purchaseCredits: function (credits) {
        return http.post('/credits/purchase', { credits: credits });
    },

    // ===== AI 开放平台 =====
    openAiModels: function (apiKey) {
        return http.get('/v1/models', { headers: { 'X-Api-Key': apiKey } });
    },
    openAiChat: function (apiKey, data) {
        return http.post('/v1/chat/completions', data, { headers: { 'X-Api-Key': apiKey } });
    },
    createApp: function (appName, description) {
        return http.post('/apps', { appName: appName, description: description });
    },
    listApps: function () {
        return http.get('/apps');
    },
    createAppKey: function (appId) {
        return http.post('/apps/' + appId + '/keys');
    },
    toggleAppKey: function (keyId, status) {
        return http.put('/apps/keys/' + keyId, { status: status });
    },
    deleteApp: function (appId) {
        return http.delete('/apps/' + appId);
    },
    adminCheck: function () {
        return http.get('/admin/check');
    },
    adminSkus: function () {
        return http.get('/admin/credit-packages');
    },
    adminCreateSku: function (sku) {
        return http.post('/admin/credit-packages', sku);
    },
    adminUpdateSku: function (sku) {
        return http.put('/admin/credit-packages', sku);
    },
    adminActivities: function () {
        return http.get('/admin/credit-activities');
    },
    adminCreateActivity: function (activity) {
        return http.post('/admin/credit-activities', activity);
    },
    adminUpdateActivity: function (activity) {
        return http.put('/admin/credit-activities', activity);
    },
    adminAdjustQuota: function (userId, amount, type) {
        return http.put('/admin/credits', { userId: userId, amount: amount, type: type });
    },
    adminCreditOverview: function () {
        return http.get('/admin/credit-overview');
    },
    adminAiCallLogs: function (current, size) {
        return http.get('/admin/ai-call-logs', { params: { current: current || 1, size: size || 10 } });
    }
};
