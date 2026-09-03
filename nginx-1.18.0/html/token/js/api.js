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

    // ===== 活动 =====
    listActivities: function () {
        return http.get('/token-activity/list');
    },
    getActivity: function (id) {
        return http.get('/token-activity/' + id);
    },

    // ===== 抢购 =====
    grant: function (skuId) {
        return http.post('/token-order/grant/' + skuId);
    },

    // ===== 订单 / 权益 =====
    myOrders: function () {
        return http.get('/token-order/user');
    },
    myQuota: function (modelId) {
        var params = modelId ? { modelId: modelId } : {};
        return http.get('/user-quota/me', { params: params });
    },

    // ===== Credits =====
    creditAccount: function () {
        return http.get('/credits/account');
    },
    rechargeCredits: function (credits) {
        return http.post('/credits/recharge', { credits: credits });
    },
    listCreditActivities: function () {
        return http.get('/credit-activities');
    },
    claimCreditPackage: function (packageId) {
        return http.post('/credit-activities/packages/' + packageId + '/claim');
    },

    // ===== AI 开放平台 =====
    listModels: function () {
        return http.get('/ai/models');
    },
    chat: function (data) {
        return http.post('/ai/chat', data);
    },
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
    billingSummary: function () {
        return http.get('/billing/summary');
    },
    billingRecords: function (changeType, current, size) {
        return http.get('/billing/records', {
            params: { changeType: changeType || '', current: current || 1, size: size || 10 }
        });
    },
    billingDaily: function (days) {
        return http.get('/billing/daily', { params: { days: days || 7 } });
    },
    adminCheck: function () {
        return http.get('/admin/check');
    },
    adminOverview: function () {
        return http.get('/admin/overview');
    },
    adminCallLogs: function (current, size) {
        return http.get('/admin/call-logs', { params: { current: current || 1, size: size || 10 } });
    },
    adminSkus: function () {
        return http.get('/admin/skus');
    },
    adminAdjustQuota: function (userId, modelId, amount, type) {
        return http.put('/admin/quota', { userId: userId, modelId: modelId, amount: amount, type: type });
    },
    adminCreditOverview: function () {
        return http.get('/admin/credit-overview');
    },
    adminAiCallLogs: function (current, size) {
        return http.get('/admin/ai-call-logs', { params: { current: current || 1, size: size || 10 } });
    }
};
