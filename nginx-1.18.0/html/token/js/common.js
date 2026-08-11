/**
 * common.js —— axios 实例 + 鉴权拦截 + 通用工具
 * 说明：页面统一走 /api 前缀，由 nginx 剥离后转发到后端 8081
 */
var http = axios.create({
    baseURL: '/api',
    timeout: 8000
});

// 请求拦截：自动携带登录 token（登录后由后端续期 120 分钟）
http.interceptors.request.use(function (config) {
    var token = sessionStorage.getItem('token');
    if (token) {
        config.headers['authorization'] = token;
    }
    return config;
});

// 响应拦截：统一解包 Result（{success, data}），业务失败转 reject
http.interceptors.response.use(function (response) {
    var result = response.data;
    if (result && result.success === false) {
        return Promise.reject(new Error(result.errorMsg || '请求失败'));
    }
    return result;
}, function (error) {
    var resp = error.response;
    if (resp && resp.status === 401) {
        // 未登录 / token 失效：清除本地 token 并跳登录页（带回跳地址）
        sessionStorage.removeItem('token');
        var redirect = encodeURIComponent(location.pathname + location.search);
        location.href = '/login.html?redirect=' + redirect;
        return Promise.reject(new Error('请先登录'));
    }
    if (resp && resp.status === 429) {
        return Promise.reject(new Error('请求过于频繁，请稍后再试'));
    }
    if (resp && resp.status === 403) {
        return Promise.reject(new Error('账号已被限制访问，请稍后再试'));
    }
    return Promise.reject(new Error('网络异常，请稍后再试'));
});

/** 统一错误提示 */
function showError(err) {
    ELEMENT.Message.error((err && err.message) ? err.message : '操作失败');
}

/** 是否已登录 */
function isLogin() {
    return !!sessionStorage.getItem('token');
}

/** 格式化 Token 数量：123456 → 12.3万；2000000 → 200万 */
function formatTokens(num) {
    num = Number(num) || 0;
    if (num >= 100000000) {
        return (num / 100000000).toFixed(1).replace(/\.0$/, '') + '亿';
    }
    if (num >= 10000) {
        return (num / 10000).toFixed(1).replace(/\.0$/, '') + '万';
    }
    return String(num);
}

/** ISO 时间或毫秒时间戳 → "2026-08-11 10:00"（缓存层可能返回两种格式） */
function formatTime(iso) {
    if (!iso) return '-';
    var s = String(iso);
    if (/^\d+$/.test(s)) {
        var d = new Date(Number(s));
        var p = function (n) { return n < 10 ? '0' + n : n; };
        return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
            ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    }
    return s.replace('T', ' ').substring(0, 16);
}

/**
 * 挂载到 Vue 原型：Vue 模板（render 函数在 with(this) 中求值）里直接调用全局函数时，
 * 渲染代理 Proxy 的 has trap 会拦截未在实例上的标识符并解析为 undefined（"xx is not a function"）。
 * 挂到原型链上即可被 with 作用域正常解析。
 */
Vue.prototype.formatTime = formatTime;
Vue.prototype.formatTokens = formatTokens;

/**
 * 公共顶栏逻辑 mixin：登录态、用户信息、管理员标记、导航与登出
 * 页面内 new Vue({ mixins: [topbarMixin], ... }) 即可复用顶栏 data/methods
 */
var topbarMixin = {
    data: function () {
        return {
            logged: isLogin(),
            me: { nickName: '' },
            isAdmin: false
        };
    },
    created: function () {
        if (this.logged) this.loadMe();
    },
    methods: {
        loadMe: function () {
            var self = this;
            api.me().then(function (res) {
                self.me = res.data || {};
            }).catch(function () {});
            api.adminCheck().then(function (res) {
                self.isAdmin = !!(res.data && res.data.admin);
            }).catch(function () {});
        },
        goLogin: function () {
            location.href = '/login.html?redirect=' + encodeURIComponent(location.pathname + location.search);
        },
        logout: function () {
            sessionStorage.removeItem('token');
            location.reload();
        }
    }
};
