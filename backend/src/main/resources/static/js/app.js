(() => {
    'use strict';

    const API = '/api/v1';
    const DISPLAY_LABELS = {
        OTHER: '其他',
        UNKNOWN: '未知',
        'UNKNOWN ARTIST': '未知艺人',
        UNKNOWN_SOURCE: '未知来源',
        ALL_COMMENTS: '全部评论',
        ALL_CONCERTS: '全部演出项目',
        CONCERTS: '演出项目',
        COMMENTS: '评论',
        POSITIVE: '正面',
        NEUTRAL: '中性',
        NEGATIVE: '负面',
        DAMAI: '大麦',
        SHOWSTART: '秀动',
        PIAONIU: '票牛',
        SUCCEEDED: '成功',
        FAILED: '失败',
        RUNNING: '运行中',
        PENDING: '等待中',
        FINISHED: '已完成',
        TICKETING: '票务',
        PRICE: '票价',
        PERFORMANCE: '演出表现',
        AUDIO_VISUAL: '视听效果',
        VENUE_SERVICE: '场馆服务',
        TRANSPORT: '交通出行',
        CRAWL_SHOWSTART: '秀动采集',
        CRAWL_PIAONIU: '票牛采集',
        CRAWL_PUBLIC: '公开页采集',
        CRAWL_DAMAI: '大麦评论采集',
    };
    const displayLabel = (value) => {
        const label = String(value ?? '').trim();
        return DISPLAY_LABELS[label.toUpperCase()] || label;
    };
    const ROUTES = {
        overview: { title: '首页概览', resources: ['health', 'dashboard'] },
        concerts: { title: '演唱会查询', resources: ['concertFilters', 'concerts'] },
        market: { title: '市场分析', resources: ['market'] },
        sentiment: { title: '舆情分析', resources: ['concertFilters', 'sentiment'] },
        recommendations: { title: '推荐', resources: ['concertFilters', 'recommendations'] },
        data: { title: '数据与任务中心', resources: ['quality'] },
    };

    const endpoints = {
        health: `${API}/health`,
        dashboard: `${API}/dashboard`,
        concerts: `${API}/concerts`,
        concertFilters: `${API}/concerts/filters`,
        calendar: `${API}/concerts/calendar`,
        market: `${API}/market`,
        sentiment: `${API}/sentiment`,
        recommendations: `${API}/recommendations`,
        quality: `${API}/data/quality`,
        jobs: `${API}/jobs`,
        comments: `${API}/comments`,
        insights: `${API}/insights`,
        exportMarket: `${API}/export/market`,
        exportRisks: `${API}/export/risks`,
        exportComments: `${API}/export/comments`,
        authMe: `${API}/auth/me`,
        adminLogs: `${API}/admin/logs`,
    };

    /**
     * 登录态只存在 sessionStorage：关掉标签页就失效，不落到磁盘上。
     * 用的是 HTTP Basic，账号密码由服务端内存用户存储校验，系统没有用户注册表。
     */
    const AUTH_KEY = 'pa-auth';
    const auth = {
        read() {
            try {
                const raw = sessionStorage.getItem(AUTH_KEY);
                if (!raw) return null;
                const parsed = JSON.parse(raw);
                return parsed && parsed.token && parsed.user ? parsed : null;
            } catch (_) {
                return null;
            }
        },
        headers() {
            const current = auth.read();
            return current ? { Authorization: `Basic ${current.token}` } : {};
        },
        isAdmin() {
            const current = auth.read();
            return Boolean(current && current.user && current.user.admin);
        },
        clear() {
            try { sessionStorage.removeItem(AUTH_KEY); } catch (_) { /* 无痕模式下会抛错，忽略 */ }
        },
    };

    const state = {
        route: 'overview',
        loaded: new Set(),
        pending: new Map(),
        charts: new Map(),
        chartData: new Map(),
        dashboard: null,
        market: null,
        sentiment: null,
        quality: null,
        filters: null,
        sentimentPlatforms: [],
        concertItems: [],
        concertPage: 1,
        concertPages: 1,
        concertTotal: 0,
        concertMode: 'list',
        calendarDate: new Date(new Date().getFullYear(), new Date().getMonth(), 1),
        calendarItems: [],
        calendarEvents: new Map(),
        calendarLoadToken: 0,
        marketTab: 'city',
        sentimentTab: 'distribution',
        dataTab: 'quality',
        jobPage: 1,
        jobPages: 1,
        logPage: 1,
        logPages: 1,
        authUser: null,
        lastDrawerFocus: null,
        detailId: null,
        globalSearchOpener: null,
        globalSearchRestoreFocus: true,
        globalSearchSequence: 0,
        calendarDialogOpener: null,
        calendarDialogRestoreFocus: true,
    };

    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
    const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
    const escapeHTML = (value) => String(value ?? '').replace(/[&<>'"]/g, (char) => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#039;', '"': '&quot;',
    })[char]);

    const getPath = (object, path) => path.split('.').reduce((value, key) => value?.[key], object);
    const pick = (object, paths, fallback = undefined) => {
        for (const path of paths) {
            const value = getPath(object, path);
            if (value !== undefined && value !== null && value !== '') return value;
        }
        return fallback;
    };
    const numberValue = (value, fallback = 0) => {
        const parsed = typeof value === 'string' ? Number(value.replaceAll(',', '').replace('%', '')) : Number(value);
        return Number.isFinite(parsed) ? parsed : fallback;
    };
    const maybeNumber = (value) => {
        if (value === undefined || value === null || value === '') return null;
        const parsed = Number(value);
        return Number.isFinite(parsed) ? parsed : null;
    };
    const arrayAt = (object, paths) => {
        for (const path of paths) {
            const value = getPath(object, path);
            if (Array.isArray(value)) return value;
        }
        return [];
    };
    const unwrap = (payload) => {
        if (!isObject(payload)) return payload;
        if (isObject(payload.data) && !Array.isArray(payload.data)) return payload.data;
        if (isObject(payload.result) && !Array.isArray(payload.result)) return payload.result;
        return payload;
    };

    const formatNumber = (value) => {
        const parsed = maybeNumber(value);
        return parsed === null ? '--' : parsed.toLocaleString('zh-CN', { maximumFractionDigits: 1 });
    };
    const formatInteger = (value) => {
        const parsed = maybeNumber(value);
        return parsed === null ? '--' : Math.round(parsed).toLocaleString('zh-CN');
    };
    const formatCurrency = (value) => {
        const parsed = maybeNumber(value);
        return parsed === null ? '票价待定' : `¥${Math.round(parsed).toLocaleString('zh-CN')}`;
    };
    const formatRatio = (value) => {
        const parsed = maybeNumber(value);
        if (parsed === null) return '--';
        const percent = Math.abs(parsed) <= 1 ? parsed * 100 : parsed;
        return `${percent.toFixed(percent >= 10 ? 0 : 1)}%`;
    };
    const formatSentiment = (value) => {
        const parsed = maybeNumber(value);
        return parsed === null ? '--' : Math.max(-1, Math.min(1, parsed)).toFixed(2);
    };
    const toDate = (value) => {
        if (!value) return null;
        const date = value instanceof Date ? value : new Date(value);
        return Number.isNaN(date.getTime()) ? null : date;
    };
    const toLocalDate = (value) => {
        const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value || ''));
        if (!match) return toDate(value);
        return new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
    };
    const formatDate = (value) => {
        const date = toDate(value);
        return date ? new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date) : '--';
    };
    const formatDateTime = (value) => {
        const date = toDate(value);
        return date ? new Intl.DateTimeFormat('zh-CN', {
            year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
        }).format(date) : '--';
    };
    const formatConcertDateTime = (value, precision) =>
        String(precision || '').toUpperCase() === 'DATE' ? formatDate(value) : formatDateTime(value);
    const formatDuration = (value) => {
        const parsed = maybeNumber(value);
        if (parsed === null) return '--';
        const milliseconds = parsed;
        if (milliseconds < 1000) return `${Math.round(milliseconds)} 毫秒`;
        if (milliseconds < 60000) return `${(milliseconds / 1000).toFixed(milliseconds < 10000 ? 1 : 0)} 秒`;
        const minutes = Math.floor(milliseconds / 60000);
        const seconds = Math.round((milliseconds % 60000) / 1000);
        return `${minutes} 分 ${seconds} 秒`;
    };
    const safeUrl = (value) => {
        try {
            const url = new URL(String(value), window.location.origin);
            return ['http:', 'https:'].includes(url.protocol) ? url.href : '';
        } catch (_) {
            return '';
        }
    };
    const truncate = (value, length = 80) => {
        const text = String(value ?? '');
        return text.length > length ? `${text.slice(0, length - 1)}…` : text;
    };

    const refreshIcons = () => {
        if (window.lucide?.createIcons) {
            window.lucide.createIcons({ attrs: { 'stroke-width': 1.8, 'aria-hidden': 'true' } });
        }
    };

    const announce = (message, type = 'info') => {
        const region = $('#toast-region');
        if (!region) return;
        const toast = document.createElement('div');
        toast.className = `toast${type === 'error' ? ' is-error' : ''}`;
        toast.textContent = message;
        region.appendChild(toast);
        window.setTimeout(() => toast.remove(), 3600);
    };

    const setSyncNote = (text) => {
        const node = $('#sync-note');
        if (node) node.textContent = text;
    };

    const setDatasetVersion = (version) => {
        if (!version) return;
        const value = String(version);
        const node = $('#dataset-version');
        if (node) node.textContent = value.toLowerCase().startsWith('sample')
            ? `本地开发样例 · ${value}`
            : `数据批次 ${value}`;
    };

    const queryFromForm = (form) => {
        const params = new URLSearchParams();
        if (!form) return params;
        new FormData(form).forEach((value, key) => {
            const clean = String(value).trim();
            if (clean) params.set(key, clean);
        });
        return params;
    };

    const requestJSON = async (url, params = null, options = {}) => {
        const target = new URL(url, window.location.origin);
        if (params) {
            for (const [key, value] of params.entries()) {
                if (value !== '' && value !== undefined && value !== null) target.searchParams.set(key, value);
            }
        }
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 15000);
        try {
            const response = await fetch(target, {
                method: 'GET',
                credentials: 'same-origin',
                headers: { Accept: 'application/json', ...auth.headers() },
                signal: controller.signal,
                ...options,
            });
            const text = await response.text();
            let payload = {};
            if (text) {
                try { payload = JSON.parse(text); } catch (_) { payload = { message: text }; }
            }
            if (!response.ok) {
                const message = pick(payload, ['message', 'detail', 'error.message', 'error'], `请求失败（${response.status}）`);
                throw new Error(typeof message === 'string' ? message : `请求失败（${response.status}）`);
            }
            return unwrap(payload);
        } catch (error) {
            if (error.name === 'AbortError') throw new Error('请求超时，请稍后重试');
            throw error;
        } finally {
            window.clearTimeout(timeout);
        }
    };

    const setViewAlert = (resource, error) => {
        const node = $(`[data-view-alert="${resource}"]`);
        if (!node) return;
        node.hidden = false;
        node.innerHTML = `<span>${escapeHTML(error?.message || '数据读取失败')}</span><button class="button" type="button" data-retry-resource="${escapeHTML(resource)}"><i data-lucide="refresh-cw"></i><span>重试</span></button>`;
        refreshIcons();
    };

    const clearViewAlert = (resource) => {
        const node = $(`[data-view-alert="${resource}"]`);
        if (!node) return;
        node.hidden = true;
        node.replaceChildren();
    };

    const setChartState = (key, status, message, resource = '') => {
        const node = $(`[data-chart-state="${key}"]`);
        if (!node) return;
        if (status === 'ready') {
            node.hidden = true;
            return;
        }
        node.hidden = false;
        node.className = `chart-state${status === 'loading' ? ' is-loading' : ''}`;
        const retry = status === 'error' && resource
            ? `<button class="button button-secondary" type="button" data-retry-resource="${escapeHTML(resource)}"><i data-lucide="refresh-cw"></i><span>重试</span></button>`
            : '';
        node.innerHTML = `${status === 'loading' ? '<span class="spinner" aria-hidden="true"></span>' : ''}<span>${escapeHTML(message)}</span>${retry}`;
        refreshIcons();
    };

    const normalizeSeries = (source, nameKeys = ['name', 'label'], valueKeys = ['value', 'count']) => {
        if (!source) return [];
        if (isObject(source)) {
            const nested = arrayAt(source, ['items', 'data', 'values', 'series', 'buckets']);
            if (nested.length) return normalizeSeries(nested, nameKeys, valueKeys);
            return Object.entries(source)
                .filter(([, value]) => typeof value === 'number' || !Number.isNaN(Number(value)))
                .map(([name, value]) => ({ name: displayLabel(name), rawName: name, value: numberValue(value) }));
        }
        if (!Array.isArray(source)) return [];
        return source.map((item, index) => {
            if (!isObject(item)) return { name: String(index + 1), value: numberValue(item) };
            const name = pick(item, nameKeys, String(index + 1));
            const value = pick(item, valueKeys, 0);
            return { name: displayLabel(name), rawName: String(name), value: numberValue(value), raw: item };
        }).filter((item) => item.name && Number.isFinite(item.value));
    };

    const chartColors = () => {
        const styles = getComputedStyle(document.documentElement);
        const read = (name) => styles.getPropertyValue(name).trim();
        return {
            primary: read('--primary'),
            primarySoft: read('--primary-soft'),
            text: read('--text-secondary'),
            strong: read('--text-primary'),
            surface: read('--bg-surface'),
            grid: read('--chart-grid'),
            neutral: read('--chart-neutral'),
            secondary: read('--chart-secondary'),
            negative: read('--chart-negative'),
            warning: read('--chart-warning'),
            positive: read('--chart-positive'),
        };
    };

    const getChart = (key) => {
        const element = $(`[data-chart="${key}"]`);
        if (!element || !window.echarts) return null;
        let chart = state.charts.get(key);
        if (!chart) {
            chart = window.echarts.init(element, null, { renderer: 'canvas' });
            state.charts.set(key, chart);
        }
        return chart;
    };

    const chartTable = (key, items, valueLabel = '数值', formatter = formatNumber) => {
        const node = $(`[data-chart-table-view="${key}"]`);
        if (!node) return;
        if (!items.length) {
            node.innerHTML = '<div class="empty-state">暂无可展示的数据</div>';
            return;
        }
        node.innerHTML = `<table><thead><tr><th>项目</th><th>${escapeHTML(valueLabel)}</th></tr></thead><tbody>${items.map((item) => `<tr><td>${escapeHTML(item.name)}</td><td>${escapeHTML(formatter(item.value))}</td></tr>`).join('')}</tbody></table>`;
    };

    const renderSeriesChart = (key, items, config = {}) => {
        const clean = items.filter((item) => Number.isFinite(item.value));
        state.chartData.set(key, { items: clean, config });
        chartTable(key, clean, config.valueLabel || '数值', config.tableFormatter || formatNumber);
        if (!clean.length || clean.every((item) => item.value === 0)) {
            const chart = getChart(key);
            chart?.off('click');
            chart?.clear();
            setChartState(key, 'empty', config.emptyMessage || '当前条件下暂无数据');
            return false;
        }
        const chart = getChart(key);
        if (!chart) {
            setChartState(key, 'error', '图表组件未加载，可切换到数据表查看');
            return false;
        }

        const colors = chartColors();
        const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;
        const labels = clean.map((item) => item.name);
        const values = clean.map((item) => item.value);
        const common = {
            animationDuration: reducedMotion ? 0 : 260,
            aria: { enabled: true, decal: { show: false } },
            textStyle: { color: colors.text, fontFamily: 'system-ui, sans-serif' },
            tooltip: {
                trigger: config.type === 'pie' ? 'item' : 'axis',
                backgroundColor: colors.surface,
                borderColor: colors.grid,
                textStyle: { color: colors.strong, fontSize: 12 },
                confine: true,
            },
        };
        let option;
        if (config.type === 'pie') {
            option = {
                ...common,
                color: config.colors || [colors.primary, colors.neutral, colors.warning, colors.negative, colors.secondary],
                legend: { bottom: 4, textStyle: { color: colors.text, fontSize: 11 }, itemWidth: 10, itemHeight: 8 },
                series: [{
                    type: 'pie',
                    radius: ['44%', '68%'],
                    center: ['50%', '43%'],
                    minAngle: 3,
                    itemStyle: { borderColor: colors.surface, borderWidth: 3 },
                    label: { color: colors.text, fontSize: 11, formatter: '{b}\n{d}%' },
                    data: clean.map((item) => ({ name: item.name, value: item.value })),
                }],
            };
        } else if (config.type === 'line') {
            option = {
                ...common,
                grid: { left: 18, right: 18, top: 24, bottom: 24, containLabel: true },
                xAxis: { type: 'category', data: labels, boundaryGap: false, axisLine: { lineStyle: { color: colors.grid } }, axisTick: { show: false }, axisLabel: { color: colors.text, fontSize: 11, hideOverlap: true } },
                yAxis: { type: 'value', min: config.min, max: config.max, splitLine: { lineStyle: { color: colors.grid } }, axisLabel: { color: colors.text, fontSize: 11, formatter: config.axisFormatter } },
                series: [{ type: 'line', data: values, smooth: false, symbolSize: 6, lineStyle: { width: 2, color: colors.primary }, itemStyle: { color: colors.primary }, areaStyle: config.area ? { color: colors.primarySoft, opacity: .55 } : undefined }],
            };
        } else {
            const horizontal = config.horizontal === true;
            const categoryAxis = { type: 'category', data: horizontal ? [...labels].reverse() : labels, axisTick: { show: false }, axisLine: { lineStyle: { color: colors.grid } }, axisLabel: { color: colors.text, fontSize: 11, hideOverlap: true, width: horizontal ? 105 : undefined, overflow: 'truncate' } };
            const valueAxis = { type: 'value', splitLine: { lineStyle: { color: colors.grid } }, axisLine: { show: false }, axisLabel: { color: colors.text, fontSize: 11, formatter: config.axisFormatter } };
            const chartValues = horizontal ? [...values].reverse() : values;
            option = {
                ...common,
                grid: { left: 16, right: horizontal ? 30 : 16, top: 20, bottom: 20, containLabel: true },
                xAxis: horizontal ? valueAxis : categoryAxis,
                yAxis: horizontal ? categoryAxis : valueAxis,
                series: [{
                    type: 'bar',
                    cursor: config.onClick ? 'pointer' : 'default',
                    data: chartValues.map((value, index) => ({ value, itemStyle: { color: colors[config.colorKeys?.[horizontal ? values.length - index - 1 : index]] || colors.primary, borderRadius: horizontal ? [0, 3, 3, 0] : [3, 3, 0, 0] } })),
                    barMaxWidth: 26,
                }],
            };
        }
        chart.setOption(option, true);
        chart.off('click');
        if (typeof config.onClick === 'function') chart.on('click', config.onClick);
        setChartState(key, 'ready');
        return true;
    };

    const rerenderCharts = () => {
        for (const [key, stored] of state.chartData.entries()) renderSeriesChart(key, stored.items, stored.config);
        window.requestAnimationFrame(() => state.charts.forEach((chart) => chart.resize()));
    };

    const summaryForSeries = (items, noun = '记录', formatter = formatNumber) => {
        if (!items.length) return '当前条件下暂无可用于摘要的数据。';
        const ranked = [...items].sort((a, b) => b.value - a.value);
        const total = items.reduce((sum, item) => sum + item.value, 0);
        return `${ranked[0].name}最高，为 ${formatter(ranked[0].value)}；共统计 ${formatter(total)} ${noun}。`;
    };

    const setSummary = (id, text) => {
        const node = document.getElementById(id);
        if (node) node.textContent = text;
    };

    const fillSelect = (selector, values, placeholder) => {
        const unique = [...new Set(values.map((value) => String(value ?? '').trim()).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'zh-CN'));
        $$(selector).forEach((select) => {
            const current = select.value;
            select.innerHTML = `<option value="">${escapeHTML(placeholder)}</option>${unique.map((value) => `<option value="${escapeHTML(value)}">${escapeHTML(displayLabel(value))}</option>`).join('')}`;
            if (unique.includes(current)) select.value = current;
        });
    };

    const extractOptions = (payload) => {
        const options = pick(payload, ['filterOptions', 'filters', 'meta.filters', 'meta'], {});
        return {
            cities: arrayAt(options, ['cities', 'cityOptions']),
            statuses: arrayAt(options, ['statuses', 'saleStatuses', 'statusOptions']),
            artists: arrayAt(options, ['artists', 'artistNames', 'artistOptions']),
            regions: arrayAt(options, ['regions', 'regionOptions', 'userRegions']),
            categories: arrayAt(options, ['categories', 'categoryOptions']),
            platforms: arrayAt(options, ['platforms', 'sources', 'platformOptions']),
        };
    };

    const applyOptions = (options, scopes = ['concerts', 'recommendations']) => {
        if (scopes.includes('concerts')) {
            if (options.cities.length) fillSelect('#concert-filter-form select[name="city"]', options.cities, '全部城市');
            if (options.statuses.length) fillSelect('#concert-filter-form select[name="status"]', options.statuses, '全部状态');
            if (options.artists.length) fillSelect('#concert-filter-form select[name="artist"]', options.artists, '全部艺人');
            if (options.categories?.length) fillSelect('#concert-filter-form select[name="category"]', options.categories, '全部分类');
        }
        if (scopes.includes('recommendations')) {
            if (options.cities.length) fillSelect('#recommendation-form select[name="city"]', options.cities, '不限城市');
            if (options.artists.length) fillSelect('#recommendation-form select[name="artist"]', options.artists, '不限艺人');
        }
        if (scopes.includes('sentiment')) {
            if (options.regions.length) fillSelect('#sentiment-filter-form select[name="region"]', options.regions, '全部地区');
            if (options.platforms.length) fillSelect('#sentiment-filter-form select[name="platform"]', options.platforms, '全部平台');
        }
    };

    const normalizeRisk = (item) => {
        const negativeRate = maybeNumber(pick(item, ['negativeRate', 'negativeRatio', 'riskRate', 'rate'], null));
        const rawLevel = String(pick(item, ['riskLevel', 'severity', 'level'], '')).toUpperCase();
        const level = rawLevel === 'ALERT' || rawLevel === 'WATCH'
            ? rawLevel
            : negativeRate !== null && negativeRate >= .4 ? 'ALERT' : 'WATCH';
        return {
            name: displayLabel(pick(item, ['objectName', 'entityName', 'artistName', 'name', 'subject'], '未知对象')),
            negativeRate,
            level,
            score: pick(item, ['averageScore', 'avgSentiment', 'sentimentScore', 'score'], null),
            topic: displayLabel(pick(item, ['primaryNegativeTopic', 'mainNegativeTopic', 'negativeTopic', 'topic', 'mainTopic'], '未标注主题')),
            samples: numberValue(pick(item, ['sampleSize', 'sampleCount', 'samples', 'commentCount', 'count'], 0)),
            batchId: String(pick(item, ['batchId', 'batch', 'datasetVersion'], '--')),
            rule: String(pick(item, ['triggerRule', 'rule', 'reason'], '负面评论情感分 ≤ -0.2')),
        };
    };
    const riskLevelLabel = (risk) => risk.level === 'ALERT' ? '重点预警' : '关注';
    const riskStatusClass = (risk) => risk.level === 'ALERT' ? 'status-danger' : 'status-watch';

    const loadHealth = async (force = false) => {
        if (state.loaded.has('health') && !force) return;
        const status = $('#service-status');
        status?.querySelector('.status-dot')?.classList.remove('is-ok', 'is-error');
        status?.querySelector('.status-dot')?.classList.add('is-loading');
        try {
            const payload = await requestJSON(endpoints.health);
            const rawStatus = String(pick(payload, ['status', 'state'], 'ok')).toLowerCase();
            const healthy = ['ok', 'up', 'healthy', 'ready'].some((value) => rawStatus.includes(value));
            const dot = status?.querySelector('.status-dot');
            dot?.classList.remove('is-loading', 'is-error', 'is-ok');
            dot?.classList.add(healthy ? 'is-ok' : 'is-error');
            const title = status?.querySelector('strong');
            const detail = status?.querySelector('small');
            if (title) title.textContent = healthy ? '服务正常' : '服务异常';
            if (detail) detail.textContent = String(pick(payload, ['service', 'application', 'database'], 'Spring Boot API'));
            const version = pick(payload, ['datasetVersion', 'batchId', 'version', 'dataVersion'], null);
            setDatasetVersion(version);
            state.loaded.add('health');
        } catch (error) {
            const dot = status?.querySelector('.status-dot');
            dot?.classList.remove('is-loading', 'is-ok');
            dot?.classList.add('is-error');
            const title = status?.querySelector('strong');
            const detail = status?.querySelector('small');
            if (title) title.textContent = '服务不可用';
            if (detail) detail.textContent = truncate(error.message, 40);
        }
    };

    const renderOverviewRisks = (risks) => {
        const node = $('#overview-risk-list');
        if (!node) return;
        node.setAttribute('aria-busy', 'false');
        if (!risks.length) {
            node.innerHTML = '<div class="empty-state">当前批次未识别到舆情风险</div>';
            return;
        }
        node.innerHTML = risks.slice(0, 5).map((risk) => `<div class="risk-row"><strong>${escapeHTML(risk.name)}</strong><span class="risk-score risk-score-${risk.level.toLowerCase()}">${escapeHTML(riskLevelLabel(risk))} ${escapeHTML(risk.negativeRate !== null ? formatRatio(risk.negativeRate) : formatSentiment(risk.score))}</span><p>${escapeHTML(risk.topic)} · ${formatInteger(risk.samples)} 条样本</p></div>`).join('');
    };

    const renderDashboard = (payload) => {
        state.dashboard = payload;
        const metrics = pick(payload, ['metrics', 'summary', 'overview'], payload);
        const risks = arrayAt(payload, ['risks', 'riskAlerts', 'riskItems', 'sentimentRisks']).map(normalizeRisk);
        const values = {
            concerts: pick(metrics, ['concerts', 'concertCount', 'totalConcerts', 'concertTotal'], null),
            comments: pick(metrics, ['comments', 'commentCount', 'totalComments', 'sampleCount'], null),
            cities: pick(metrics, ['cities', 'cityCount', 'totalCities'], null),
            risks: pick(metrics, ['activeRisks', 'risks', 'riskCount', 'riskObjects'], risks.length),
        };
        Object.entries(values).forEach(([key, value]) => {
            const node = $(`[data-metric="${key}"]`);
            if (node) node.textContent = formatInteger(value);
        });
        $('#overview-metrics')?.setAttribute('aria-busy', 'false');

        const averageSentiment = maybeNumber(pick(metrics, ['averageSentiment', 'averageScore', 'avgSentiment', 'sentimentScore'], null));
        const heroIndex = $('#hero-index');
        if (heroIndex) {
            const normalizedSentiment = averageSentiment === null
                ? null
                : Math.round((Math.max(-1, Math.min(1, averageSentiment)) + 1) * 50);
            heroIndex.textContent = normalizedSentiment === null ? '--' : String(normalizedSentiment);
        }

        const trend = normalizeSeries(
            pick(payload, ['trend', 'monthlyTrend', 'marketTrend', 'charts.trend', 'charts.monthly'], []),
            ['month', 'period', 'date', 'name', 'label'],
            ['concertCount', 'count', 'value', 'total'],
        );
        renderSeriesChart('overview-trend', trend, { type: 'line', valueLabel: '项目数', area: true });
        setSummary('overview-trend-summary', summaryForSeries(trend, '场', formatInteger));
        renderOverviewRisks(risks);

        const riskBadge = $('#risk-nav-count');
        if (riskBadge) {
            const riskCount = numberValue(values.risks);
            riskBadge.textContent = formatInteger(riskCount);
            riskBadge.hidden = riskCount === 0;
        }

        const batch = pick(payload, ['latestBatch', 'recentBatch', 'batch', 'dataBatch'], {});
        const batchNode = $('#overview-batch');
        if (batchNode) {
            const batchId = pick(batch, ['batchId', 'id', 'name'], pick(payload, ['datasetVersion'], '--'));
            const batchTime = pick(batch, ['finishedAt', 'processedAt', 'publishedAt', 'createdAt'], null);
            const batchCount = pick(batch, ['successCount', 'processedCount', 'recordCount'], null);
            batchNode.innerHTML = `<span>当前批次</span><strong>${escapeHTML(String(batchId || '--'))}</strong><small>${escapeHTML(batchTime ? formatDateTime(batchTime) : '时间待记录')}${batchCount !== null ? ` · ${formatInteger(batchCount)} 条` : ''}</small>`;
        }

        const version = pick(payload, ['datasetVersion', 'batch.batchId', 'latestBatch.batchId'], null);
        setDatasetVersion(version);
        applyOptions(extractOptions(payload));
    };

    const loadDashboard = async (force = false) => {
        if (state.loaded.has('dashboard') && !force) return;
        setChartState('overview-trend', 'loading', '正在读取趋势数据');
        $('#overview-metrics')?.setAttribute('aria-busy', 'true');
        clearViewAlert('dashboard');
        try {
            const payload = await requestJSON(endpoints.dashboard);
            renderDashboard(payload);
            state.loaded.add('dashboard');
            setSyncNote(`更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())}`);
        } catch (error) {
            setViewAlert('dashboard', error);
            setChartState('overview-trend', 'error', '趋势数据读取失败', 'dashboard');
            $('#overview-metrics')?.setAttribute('aria-busy', 'false');
            renderOverviewRisks([]);
        }
    };

    const renderConcertFilters = (payload) => {
        const options = {
            cities: arrayAt(payload, ['cities']),
            statuses: arrayAt(payload, ['statuses']),
            artists: arrayAt(payload, ['artists']),
            categories: arrayAt(payload, ['categories']),
            platforms: state.sentimentPlatforms,
        };
        state.filters = options;
        applyOptions(options, ['concerts', 'recommendations', 'sentiment']);
        const minPrice = maybeNumber(pick(payload, ['minPrice'], null));
        const maxPrice = maybeNumber(pick(payload, ['maxPrice'], null));
        const minInput = $('#concert-filter-form input[name="minPrice"]');
        const maxInput = $('#concert-filter-form input[name="maxPrice"]');
        if (minInput && minPrice !== null) minInput.placeholder = `最低 ${Math.round(minPrice)}`;
        if (maxInput && maxPrice !== null) maxInput.placeholder = `最高 ${Math.round(maxPrice)}`;
    };

    const loadConcertFilters = async (force = false) => {
        if (state.loaded.has('concertFilters') && !force) return;
        try {
            const payload = await requestJSON(endpoints.concertFilters);
            renderConcertFilters(payload);
            state.loaded.add('concertFilters');
        } catch (error) {
            announce(`筛选选项读取失败：${error.message}`, 'error');
        }
    };

    const normalizeConcert = (item) => ({
        id: pick(item, ['id', 'concertId', 'concert_id'], ''),
        name: String(pick(item, ['concertName', 'name', 'title', 'concert_name'], '未命名演出')),
        artist: displayLabel(pick(item, ['artistName', 'artist', 'performer', 'artist_name'], '艺人待定')),
        category: String(pick(item, ['category', 'concertCategory', 'type'], '分类待定')),
        city: displayLabel(pick(item, ['city', 'cityName'], '城市待定')),
        venue: String(pick(item, ['venue', 'venueName', 'address'], '场馆待定')),
        showTime: pick(item, ['showTime', 'showDate', 'date', 'show_time'], null),
        showTimePrecision: String(pick(item, ['showTimePrecision', 'show_time_precision'], 'DATETIME')),
        minPrice: pick(item, ['minPrice', 'priceMin', 'price', 'min_price'], null),
        maxPrice: pick(item, ['maxPrice', 'priceMax', 'max_price'], null),
        originalPrice: String(pick(item, ['originalPriceText', 'priceText', 'originalPrice'], '')),
        status: String(pick(item, ['saleStatus', 'status', 'sale_status'], '状态待定')),
        source: String(pick(item, ['sourceType', 'source', 'platform'], '')),
        sourceUrl: String(pick(item, ['sourceUrl', 'url', 'source_url'], '')),
        commentCount: pick(item, ['commentCount', 'comments', 'comment_count'], null),
        raw: item,
    });

    const statusClass = (status) => {
        const text = String(status).toLowerCase();
        if (/失败|异常|取消|售罄|failed|error/.test(text)) return 'status-danger';
        if (/待|预售|即将|running|queued|pending/.test(text)) return 'status-warning';
        if (/在售|售票中|去购票|完成|成功|开放|success|succeeded|completed|available|published/.test(text)) return 'status-success';
        return '';
    };

    const renderConcerts = (payload) => {
        const items = (Array.isArray(payload) ? payload : arrayAt(payload, ['items', 'concerts', 'content', 'records', 'results'])).map(normalizeConcert);
        const pagination = isObject(payload) ? pick(payload, ['pagination', 'pageInfo', 'page'], {}) : {};
        const apiPage = pick(payload, ['page', 'number', 'pageNumber', 'pagination.page', 'pageInfo.page'], null);
        if (apiPage !== null) state.concertPage = numberValue(apiPage) + (getPath(payload, 'page') !== undefined || getPath(payload, 'number') !== undefined ? 1 : 0);
        state.concertTotal = numberValue(pick(payload, ['totalElements', 'total', 'totalCount', 'pagination.total', 'pageInfo.total'], items.length));
        state.concertPages = Math.max(1, numberValue(pick(payload, ['totalPages', 'pages', 'pagination.totalPages', 'pageInfo.pages'], Math.ceil(state.concertTotal / 10) || 1), 1));
        state.concertItems = items;
        const body = $('#concert-table-body');
        if (body) {
            body.innerHTML = items.length ? items.map((concert) => `<tr><td><div class="cell-stack"><strong>${escapeHTML(concert.name)}</strong><small>${escapeHTML(concert.artist)}</small></div></td><td><div class="cell-stack"><span>${escapeHTML(concert.city)}</span><small title="${escapeHTML(concert.venue)}">${escapeHTML(concert.venue)}</small></div></td><td>${escapeHTML(formatDate(concert.showTime))}</td><td><div class="cell-stack"><span>${escapeHTML(formatCurrency(concert.minPrice))}${concert.maxPrice !== null && concert.maxPrice !== undefined ? ` - ${escapeHTML(formatCurrency(concert.maxPrice))}` : ''}</span><small>${escapeHTML(concert.originalPrice)}</small></div></td><td><span class="status-pill ${statusClass(concert.status)}">${escapeHTML(displayLabel(concert.status))}</span></td><td><button class="row-action" type="button" data-concert-detail="${escapeHTML(concert.id)}" aria-label="查看 ${escapeHTML(concert.name)} 详情" title="查看详情"><i data-lucide="panel-right-open"></i></button></td></tr>`).join('') : '<tr><td colspan="6"><div class="empty-state">当前条件下没有演出项目</div></td></tr>';
        }
        $('#concert-result-count').textContent = formatInteger(state.concertTotal);
        $('#concert-page-label').textContent = `第 ${state.concertPage} / ${state.concertPages} 页`;
        $('[data-concert-page="prev"]').disabled = state.concertPage <= 1;
        $('[data-concert-page="next"]').disabled = state.concertPage >= state.concertPages;
        const filters = queryFromForm($('#concert-filter-form'));
        $('#concert-filter-summary').textContent = filters.size ? [...filters.values()].join(' · ') : '全部演出项目';
        const derived = {
            cities: items.map((item) => item.city).filter((value) => value !== '城市待定'),
            statuses: items.map((item) => item.status).filter((value) => value !== '状态待定'),
            artists: items.map((item) => item.artist).filter((value) => value !== '艺人待定'),
            categories: items.map((item) => item.category).filter((value) => value !== '分类待定'),
            platforms: [],
        };
        const options = extractOptions(payload);
        if (!state.filters) {
            applyOptions({
                cities: options.cities.length ? options.cities : derived.cities,
                statuses: options.statuses.length ? options.statuses : derived.statuses,
                artists: options.artists.length ? options.artists : derived.artists,
                categories: options.categories.length ? options.categories : derived.categories,
                platforms: options.platforms,
            }, ['concerts']);
        }
        refreshIcons();
    };

    const loadConcerts = async (force = false) => {
        if (state.loaded.has('concerts') && !force) return;
        clearViewAlert('concerts');
        const body = $('#concert-table-body');
        if (body) body.innerHTML = '<tr><td colspan="6"><div class="table-state"><span class="spinner" aria-hidden="true"></span>正在读取演出项目</div></td></tr>';
        const params = queryFromForm($('#concert-filter-form'));
        params.set('page', String(Math.max(0, state.concertPage - 1)));
        params.set('size', '10');
        try {
            const payload = await requestJSON(endpoints.concerts, params);
            renderConcerts(payload);
            state.loaded.add('concerts');
            setSyncNote(`更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())}`);
        } catch (error) {
            setViewAlert('concerts', error);
            if (body) body.innerHTML = `<tr><td colspan="6"><div class="empty-state">${escapeHTML(error.message)}</div></td></tr>`;
        }
    };

    const monthBounds = (date) => {
        const start = new Date(date.getFullYear(), date.getMonth(), 1);
        const end = new Date(date.getFullYear(), date.getMonth() + 1, 0);
        const iso = (value) => `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
        return { start, end, startText: iso(start), endText: iso(end) };
    };

    const renderCalendar = (items) => {
        const target = $('#concert-calendar');
        if (!target) return;
        const { start, end } = monthBounds(state.calendarDate);
        $('#calendar-title').textContent = `${start.getFullYear()} 年 ${start.getMonth() + 1} 月`;
        const grouped = new Map();
        items.forEach((concert) => {
            const date = toDate(concert.showTime);
            if (!date) return;
            const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
            if (!grouped.has(key)) grouped.set(key, []);
            grouped.get(key).push(concert);
        });
        state.calendarEvents = grouped;
        const firstWeekday = (start.getDay() + 6) % 7;
        const cells = [];
        const previousEnd = new Date(start.getFullYear(), start.getMonth(), 0).getDate();
        for (let offset = firstWeekday - 1; offset >= 0; offset -= 1) {
            cells.push(`<div class="calendar-day is-outside"><span class="calendar-day-number">${previousEnd - offset}</span></div>`);
        }
        for (let day = 1; day <= end.getDate(); day += 1) {
            const key = `${start.getFullYear()}-${String(start.getMonth() + 1).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
            const events = grouped.get(key) || [];
            const first = events[0];
            cells.push(first
                ? `<button class="calendar-day has-events" type="button" data-calendar-day="${key}" aria-label="${day} 日，${events.length} 场演出"><span class="calendar-day-number">${day}</span><span class="calendar-event">${escapeHTML(first.name)}</span><span class="calendar-count">${events.length} 场</span></button>`
                : `<div class="calendar-day"><span class="calendar-day-number">${day}</span></div>`);
        }
        while (cells.length % 7 !== 0 || cells.length < 35) {
            const day = cells.length - firstWeekday - end.getDate() + 1;
            cells.push(`<div class="calendar-day is-outside"><span class="calendar-day-number">${day}</span></div>`);
        }
        target.innerHTML = cells.join('');
    };

    const loadCalendar = async () => {
        const target = $('#concert-calendar');
        if (target) target.innerHTML = '<div class="loading-block" style="grid-column:1/-1"><span class="spinner" aria-hidden="true"></span>正在读取日历</div>';
        const loadToken = ++state.calendarLoadToken;
        const params = queryFromForm($('#concert-filter-form'));
        const selectedStart = toLocalDate(params.get('startDate'));
        const selectedEnd = toLocalDate(params.get('endDate'));
        params.delete('startDate');
        params.delete('endDate');
        params.set('year', String(state.calendarDate.getFullYear()));
        params.set('month', String(state.calendarDate.getMonth() + 1));
        try {
            const payload = await requestJSON(endpoints.calendar, params);
            if (loadToken !== state.calendarLoadToken) return;
            state.calendarItems = (Array.isArray(payload) ? payload : arrayAt(payload, ['items', 'concerts', 'content', 'records', 'results']))
                .map(normalizeConcert)
                .filter((concert) => {
                    const showDate = toDate(concert.showTime);
                    if (!showDate) return false;
                    if (selectedStart && showDate < selectedStart) return false;
                    if (selectedEnd) {
                        const endOfDay = new Date(selectedEnd);
                        endOfDay.setHours(23, 59, 59, 999);
                        if (showDate > endOfDay) return false;
                    }
                    return true;
                });
            renderCalendar(state.calendarItems);
            refreshIcons();
        } catch (error) {
            if (loadToken !== state.calendarLoadToken) return;
            if (target) target.innerHTML = `<div class="empty-state" style="grid-column:1/-1">${escapeHTML(error.message)}</div>`;
            setViewAlert('concerts', error);
        }
    };

    const concertChoice = (concert, dataAttribute) => `<button class="concert-choice" type="button" ${dataAttribute}="${escapeHTML(concert.id)}"><span><strong>${escapeHTML(concert.name)}</strong><small>${escapeHTML(concert.artist)} · ${escapeHTML(concert.city)} · ${escapeHTML(formatDate(concert.showTime))}</small></span><i data-lucide="arrow-up-right"></i></button>`;

    const openGlobalSearch = () => {
        const dialog = $('#global-search-dialog');
        if (!dialog || dialog.open) return;
        state.globalSearchOpener = document.activeElement;
        state.globalSearchRestoreFocus = true;
        dialog.showModal();
        window.requestAnimationFrame(() => $('#global-search-input')?.focus());
    };

    const closeGlobalSearch = (restoreFocus = true) => {
        const dialog = $('#global-search-dialog');
        if (!dialog?.open) return;
        state.globalSearchRestoreFocus = restoreFocus;
        dialog.close();
    };

    const runGlobalSearch = async () => {
        const input = $('#global-search-input');
        const target = $('#global-search-results');
        const query = input?.value.trim() || '';
        if (!target || !query) return;
        const sequence = ++state.globalSearchSequence;
        target.setAttribute('aria-busy', 'true');
        target.innerHTML = '<div class="loading-block"><span class="spinner" aria-hidden="true"></span><span>正在搜索演出项目</span></div>';
        try {
            const payload = await requestJSON(endpoints.concerts, new URLSearchParams({ q: query, page: '0', size: '8' }));
            if (sequence !== state.globalSearchSequence) return;
            const items = (Array.isArray(payload) ? payload : arrayAt(payload, ['items', 'concerts', 'content', 'records', 'results'])).map(normalizeConcert);
            target.innerHTML = items.length
                ? items.map((concert) => concertChoice(concert, 'data-search-concert')).join('')
                : `<div class="empty-state">未找到与“${escapeHTML(query)}”相关的演出项目</div>`;
            refreshIcons();
        } catch (error) {
            if (sequence !== state.globalSearchSequence) return;
            target.innerHTML = `<div class="empty-state">${escapeHTML(error.message)}</div>`;
        } finally {
            if (sequence === state.globalSearchSequence) target.removeAttribute('aria-busy');
        }
    };

    const openCalendarDay = (dateKey) => {
        const dialog = $('#calendar-day-dialog');
        const events = state.calendarEvents.get(dateKey) || [];
        if (!dialog || !events.length) return;
        state.calendarDialogOpener = document.activeElement;
        state.calendarDialogRestoreFocus = true;
        const date = toDate(`${dateKey}T00:00:00`);
        $('#calendar-day-title').textContent = `${formatDate(date)} · ${events.length} 场`;
        $('#calendar-day-results').innerHTML = events.map((concert) => concertChoice(concert, 'data-calendar-concert')).join('');
        dialog.showModal();
        refreshIcons();
        window.requestAnimationFrame(() => $('.concert-choice', dialog)?.focus());
    };

    const closeCalendarDay = (restoreFocus = true) => {
        const dialog = $('#calendar-day-dialog');
        if (!dialog?.open) return;
        state.calendarDialogRestoreFocus = restoreFocus;
        dialog.close();
    };

    const openDrawer = (focusOrigin = document.activeElement) => {
        const drawer = $('#detail-drawer');
        state.lastDrawerFocus = focusOrigin;
        $('.drawer-backdrop').hidden = false;
        drawer.classList.add('is-open');
        drawer.setAttribute('aria-hidden', 'false');
        $('#app-shell').inert = true;
        document.body.classList.add('drawer-open');
        window.requestAnimationFrame(() => $('[data-drawer-close]', drawer)?.focus());
    };

    const closeDrawer = () => {
        const drawer = $('#detail-drawer');
        drawer.classList.remove('is-open');
        drawer.setAttribute('aria-hidden', 'true');
        $('.drawer-backdrop').hidden = true;
        $('#app-shell').inert = false;
        document.body.classList.remove('drawer-open');
        if (state.lastDrawerFocus instanceof HTMLElement) state.lastDrawerFocus.focus();
    };

    const renderDetail = (payload) => {
        const root = pick(payload, ['concert', 'item', 'detail'], payload);
        const concert = normalizeConcert(root);
        const prices = arrayAt(payload, ['prices', 'priceDetails', 'ticketPrices']);
        const risks = arrayAt(payload, ['risks', 'riskItems']).map(normalizeRisk);
        const commentScopeNotice = String(pick(payload, ['commentScopeNotice'], '评论用于平台、地区、主题和可验证艺人范围聚合，未建立具体演出场次关联。'));
        const title = $('#detail-title');
        if (title) title.textContent = concert.name;
        const sourceUrl = safeUrl(concert.sourceUrl);
        const sourceLabel = '查看来源记录';
        $('#detail-body').innerHTML = `
            <section class="detail-section"><div class="detail-facts">
                <div class="detail-fact"><span>艺人</span><strong>${escapeHTML(concert.artist)}</strong></div>
                <div class="detail-fact"><span>分类</span><strong>${escapeHTML(displayLabel(concert.category))}</strong></div>
                <div class="detail-fact"><span>城市</span><strong>${escapeHTML(concert.city)}</strong></div>
                <div class="detail-fact"><span>场馆</span><strong>${escapeHTML(concert.venue)}</strong></div>
                <div class="detail-fact"><span>演出时间</span><strong>${escapeHTML(formatConcertDateTime(concert.showTime, concert.showTimePrecision))}</strong></div>
                <div class="detail-fact"><span>票价</span><strong>${escapeHTML(formatCurrency(concert.minPrice))}${concert.maxPrice !== null && concert.maxPrice !== undefined ? ` - ${escapeHTML(formatCurrency(concert.maxPrice))}` : ''}</strong></div>
                <div class="detail-fact"><span>售票状态</span><strong><span class="status-pill ${statusClass(concert.status)}">${escapeHTML(displayLabel(concert.status))}</span></strong></div>
            </div></section>
            ${prices.length ? `<section class="detail-section"><h3>票价档位</h3><div class="reason-list">${prices.map((price) => `<span>${escapeHTML(String(pick(price, ['name', 'label', 'seat'], '票档')))} · ${escapeHTML(formatCurrency(pick(price, ['price', 'value', 'amount'], null)))}</span>`).join('')}</div></section>` : ''}
            ${risks.length ? `<section class="detail-section"><h3>关联风险</h3><div class="detail-risks">${risks.map((risk) => `<article><div><strong>${escapeHTML(risk.name)}</strong><span class="status-pill ${riskStatusClass(risk)}">${escapeHTML(riskLevelLabel(risk))} ${escapeHTML(formatRatio(risk.negativeRate))}</span></div><p>${escapeHTML(risk.topic)} · ${escapeHTML(formatInteger(risk.samples))} 条样本</p><small>${escapeHTML(risk.rule)}</small></article>`).join('')}</div></section>` : ''}
            <section class="detail-section"><h3>评论数据范围</h3><p class="detail-note">${escapeHTML(commentScopeNotice)}</p></section>
            ${sourceUrl ? `<a class="button button-secondary" href="${escapeHTML(sourceUrl)}" target="_blank" rel="noopener noreferrer"><i data-lucide="external-link"></i><span>${sourceLabel}</span></a>` : ''}`;
        $('#detail-body').setAttribute('aria-busy', 'false');
        refreshIcons();
    };

    const loadConcertDetail = async (id, focusOrigin = null) => {
        if (id === undefined || id === null || id === '') return;
        const drawer = $('#detail-drawer');
        const wasOpen = drawer.classList.contains('is-open');
        state.detailId = id;
        $('#detail-body').setAttribute('aria-busy', 'true');
        if (!wasOpen) {
            $('#detail-title').textContent = '演唱会信息';
            $('#detail-body').innerHTML = '<div class="loading-block"><span class="spinner" aria-hidden="true"></span><span>正在读取详情</span></div>';
            openDrawer(focusOrigin || document.activeElement);
        }
        try {
            const payload = await requestJSON(`${endpoints.concerts}/${encodeURIComponent(id)}`);
            renderDetail(payload);
        } catch (error) {
            $('#detail-body').innerHTML = `<div class="empty-state"><div><p>${escapeHTML(error.message)}</p><button class="button button-secondary" type="button" data-detail-retry><i data-lucide="refresh-cw"></i><span>重试</span></button></div></div>`;
            $('#detail-body').setAttribute('aria-busy', 'false');
            refreshIcons();
        }
    };

    // ===== 原始评论下钻 =====
    const commentView = { params: {}, page: 0, size: 20, lastFocus: null };

    const commentBandText = (band) => ({
        NEGATIVE: '负面', POSITIVE: '正面', NEUTRAL: '中性', UNSCORED: '未判定',
    })[String(band || '').toUpperCase()] || '全部';

    const closeCommentDrawer = (restoreFocus = true) => {
        const drawer = $('#comment-drawer');
        if (!drawer) return;
        drawer.classList.remove('is-open');
        drawer.setAttribute('aria-hidden', 'true');
        const backdrop = $('#comment-backdrop');
        if (backdrop) backdrop.hidden = true;
        $('#app-shell').inert = false;
        document.body.classList.remove('drawer-open');
        resetInsight();
        if (restoreFocus && commentView.lastFocus instanceof HTMLElement) commentView.lastFocus.focus();
    };

    const renderCommentList = (payload) => {
        const body = $('#comment-drawer-body');
        if (!body) return;
        const pageInfo = pick(payload, ['page'], {}) || {};
        const items = arrayAt(pageInfo, ['items']);
        const total = numberValue(pick(payload, ['matchedSamples'], items.length));
        const version = String(pick(payload, ['datasetVersion'], ''));
        const notice = String(pick(payload, ['scopeNotice'], ''));
        const current = numberValue(pick(pageInfo, ['page', 'number'], 0));
        const totalPages = numberValue(pick(pageInfo, ['totalPages'], 1));
        const isLast = Boolean(pick(pageInfo, ['last'], true));

        const head = `<div class="comment-scope">
            <div><span>命中样本</span><strong>${formatInteger(total)}</strong></div>
            <div><span>数据批次</span><strong>${escapeHTML(version)}</strong></div>
        </div>
        <p class="detail-note">${escapeHTML(notice)}</p>`;

        if (!items.length) {
            body.innerHTML = `${head}<div class="empty-state">当前条件下没有匹配的评论样本</div>`;
            body.setAttribute('aria-busy', 'false');
            return;
        }

        const rows = items.map((item) => {
            const band = String(pick(item, ['sentimentLabel'], 'unscored')).toLowerCase();
            const score = maybeNumber(pick(item, ['sentimentScore'], null));
            const scoreText = score === null ? '' : ` ${score.toFixed(2)}`;
            return `<article class="comment-row">
                <p>${escapeHTML(String(pick(item, ['text'], '')))}</p>
                <footer>
                    <span class="comment-band comment-band-${escapeHTML(band)}">${escapeHTML(commentBandText(band))}${escapeHTML(scoreText)}</span>
                    <span>${escapeHTML(displayLabel(String(pick(item, ['primaryTopic'], 'other'))))}</span>
                    <span>${escapeHTML(String(pick(item, ['userRegion'], '未知地区')))}</span>
                    <span>赞 ${formatInteger(pick(item, ['likeCount'], 0))}</span>
                </footer>
            </article>`;
        }).join('');

        const pager = `<div class="detail-pager">
            <button class="button button-secondary" type="button" data-comment-page="prev" ${current <= 0 ? 'disabled' : ''}>上一页</button>
            <span>第 ${current + 1} / ${Math.max(totalPages, 1)} 页</span>
            <button class="button button-secondary" type="button" data-comment-page="next" ${isLast ? 'disabled' : ''}>下一页</button>
        </div>`;

        body.innerHTML = `${head}<div class="comment-list">${rows}</div>${pager}`;
        body.setAttribute('aria-busy', 'false');
        refreshIcons();
    };

    const loadComments = async (page = 0) => {
        const body = $('#comment-drawer-body');
        if (!body) return;
        commentView.page = Math.max(page, 0);
        body.setAttribute('aria-busy', 'true');
        body.innerHTML = '<div class="loading-block"><span class="spinner" aria-hidden="true"></span><span>正在读取评论样本</span></div>';
        try {
            const query = new URLSearchParams();
            Object.entries(commentView.params).forEach(([key, value]) => {
                if (value !== null && value !== undefined && value !== '') query.set(key, value);
            });
            query.set('page', String(commentView.page));
            query.set('size', String(commentView.size));
            const payload = await requestJSON(endpoints.comments, query);
            renderCommentList(payload);
        } catch (error) {
            body.innerHTML = `<div class="empty-state"><div><p>${escapeHTML(error.message)}</p><button class="button button-secondary" type="button" data-comment-retry><i data-lucide="refresh-cw"></i><span>重试</span></button></div></div>`;
            body.setAttribute('aria-busy', 'false');
            refreshIcons();
        }
    };

    const openCommentDrawer = (params, title, focusOrigin = null) => {
        const drawer = $('#comment-drawer');
        if (!drawer) return;
        commentView.params = params || {};
        commentView.lastFocus = focusOrigin || document.activeElement;
        const heading = $('#comment-drawer-title');
        if (heading) heading.textContent = title || '评论样本';
        drawer.classList.add('is-open');
        drawer.setAttribute('aria-hidden', 'false');
        const backdrop = $('#comment-backdrop');
        if (backdrop) backdrop.hidden = false;
        $('#app-shell').inert = true;
        document.body.classList.add('drawer-open');
        loadComments(0);
        loadInsight(commentView.params.artist || '');
    };

    // ===== 风险对象解读 =====
    // 只对风险对象出解读；模型不可用时后端直接返回模板文案，这里不做任何报错提示。
    const insightView = { name: '', token: 0 };

    const resetInsight = () => {
        const block = $('#insight-block');
        if (!block) return;
        insightView.name = '';
        insightView.token += 1;
        block.hidden = true;
        const body = $('#insight-body');
        if (body) body.innerHTML = '';
        const source = $('#insight-source');
        if (source) {
            source.textContent = '';
            source.className = 'insight-source';
        }
    };

    const renderInsight = (payload) => {
        const body = $('#insight-body');
        const sourceTag = $('#insight-source');
        if (!body) return;
        const text = String(pick(payload, ['text'], '') || '').trim();
        const source = String(pick(payload, ['source'], 'none') || 'none');
        if (!text) {
            body.innerHTML = '<p class="insight-empty">暂无解读</p>';
            body.setAttribute('aria-busy', 'false');
            return;
        }
        const rawQuotes = pick(payload, ['quotes'], []);
        const quotes = Array.isArray(rawQuotes) ? rawQuotes : [];
        if (sourceTag) {
            sourceTag.textContent = source === 'model' ? '模型生成' : '规则模板生成';
            sourceTag.className = source === 'model'
                ? 'insight-source insight-source-model'
                : 'insight-source';
        }
        const quoteRows = quotes.slice(0, 3).map((quote) => `<li>${escapeHTML(String(quote))}</li>`).join('');
        body.innerHTML = `<p class="insight-text">${escapeHTML(text)}</p>${quoteRows ? `<ul class="insight-quotes">${quoteRows}</ul>` : ''}`;
        body.setAttribute('aria-busy', 'false');
    };

    const loadInsight = async (name) => {
        const block = $('#insight-block');
        const body = $('#insight-body');
        if (!block || !body || !name) {
            resetInsight();
            return;
        }
        insightView.name = name;
        const token = ++insightView.token;
        block.hidden = false;
        body.setAttribute('aria-busy', 'true');
        body.innerHTML = '<div class="loading-block"><span class="spinner" aria-hidden="true"></span><span>正在生成解读</span></div>';
        try {
            const query = new URLSearchParams();
            query.set('object', name);
            const payload = await requestJSON(endpoints.insights, query);
            if (token !== insightView.token) return;
            renderInsight(payload);
        } catch (error) {
            // 解读失败不遮挡评论列表：直接显示“暂无”，不把异常抛给用户
            if (token !== insightView.token) return;
            body.innerHTML = '<p class="insight-empty">暂无解读</p>';
            body.setAttribute('aria-busy', 'false');
        }
    };

    const marketSeries = (payload, tab) => {
        if (tab === 'trend') {
            return {
                primary: normalizeSeries(pick(payload, ['trend', 'monthlyTrend', 'marketTrend', 'charts.trend'], []), ['month', 'period', 'date', 'name'], ['concertCount', 'count', 'value']),
                secondary: normalizeSeries(pick(payload, ['weekdays', 'weekdayDistribution', 'charts.weekdays'], []), ['weekday', 'day', 'name', 'label'], ['count', 'concertCount', 'value']),
                primaryTitle: '月度演出趋势', primaryKicker: '时间走势', primaryType: 'line',
                secondaryTitle: '星期演出分布', secondaryKicker: '演出节奏', secondaryType: 'bar',
                primaryValue: '项目数', primaryFormatter: formatInteger,
                secondaryValue: '项目数', secondaryFormatter: formatInteger,
            };
        }
        if (tab === 'price') {
            return {
                primary: normalizeSeries(pick(payload, ['prices', 'priceDistribution', 'priceRanges', 'charts.prices'], []), ['range', 'bucket', 'name', 'label'], ['count', 'concertCount', 'value']),
                secondary: normalizeSeries(pick(payload, ['cityPrices', 'averagePriceByCity', 'cityAveragePrices', 'charts.cityPrice'], []), ['cityName', 'city', 'name'], ['averagePrice', 'avgPrice', 'price', 'value']),
                primaryTitle: '票价区间项目', primaryKicker: '价格结构', primaryType: 'bar',
                secondaryTitle: '城市平均票价', secondaryKicker: '票价水平', secondaryType: 'bar',
                primaryValue: '项目数', primaryFormatter: formatInteger,
                secondaryValue: '平均票价', secondaryFormatter: formatCurrency,
            };
        }
        if (tab === 'artist') {
            return {
                primary: normalizeSeries(pick(payload, ['artists', 'artistHeat', 'artistRanking', 'charts.artists'], []), ['artistName', 'artist', 'name'], ['heatScore', 'score', 'concertCount', 'count', 'value']),
                secondary: normalizeSeries(pick(payload, ['venues', 'venueRanking', 'popularVenues', 'charts.venues'], []), ['venueName', 'venue', 'name'], ['concertCount', 'count', 'value']),
                primaryTitle: '艺人热度排行', primaryKicker: '演出与互动', primaryType: 'bar',
                secondaryTitle: '热门场馆排行', secondaryKicker: '场馆表现', secondaryType: 'bar',
            };
        }
        return {
            primary: normalizeSeries(pick(payload, ['cities', 'cityMarket', 'cityDistribution', 'charts.city'], []), ['cityName', 'city', 'name'], ['concertCount', 'count', 'value']),
            secondary: normalizeSeries(pick(payload, ['cityPrices', 'averagePriceByCity', 'cityAveragePrices', 'charts.cityPrice'], []), ['cityName', 'city', 'name'], ['averagePrice', 'avgPrice', 'price', 'value']),
            primaryTitle: '城市演出项目', primaryKicker: '项目规模', primaryType: 'bar',
            secondaryTitle: '城市平均票价', secondaryKicker: '票价水平', secondaryType: 'bar',
            primaryValue: '项目数', primaryFormatter: formatInteger,
            secondaryValue: '平均票价', secondaryFormatter: formatCurrency,
        };
    };

    const drillToCity = (city) => {
        if (!city || city === '全部') return;
        const select = $('#concert-filter-form select[name="city"]');
        if (select) {
            const existing = [...select.options].some((option) => option.value === city);
            if (!existing) select.add(new Option(city, city));
            select.value = city;
        }
        state.concertPage = 1;
        state.loaded.delete('concerts');
        announce(`已按${city}筛选演唱会`);
        if (routeFromHash() === 'concerts') showRoute('concerts', true);
        else window.location.hash = 'concerts';
    };

    const renderMarket = () => {
        if (!state.market) return;
        const data = marketSeries(state.market, state.marketTab);
        const limit = 12;
        const primary = data.primaryType === 'bar' && data.primary.length > limit
            ? [...data.primary].sort((a, b) => b.value - a.value).slice(0, limit)
            : data.primary;
        const secondary = data.secondaryType === 'bar' && data.secondary.length > limit
            ? [...data.secondary].sort((a, b) => b.value - a.value).slice(0, limit)
            : data.secondary;
        $('#market-primary-title').textContent = `${data.primaryTitle}${primary.length < data.primary.length ? ` · TOP ${limit}` : ''}`;
        $('#market-primary-kicker').textContent = data.primaryKicker;
        $('#market-secondary-title').textContent = `${data.secondaryTitle}${secondary.length < data.secondary.length ? ` · TOP ${limit}` : ''}`;
        $('#market-secondary-kicker').textContent = data.secondaryKicker;
        renderSeriesChart('market-primary', primary, {
            type: data.primaryType,
            horizontal: data.primaryType === 'bar',
            valueLabel: data.primaryValue || '数值',
            tableFormatter: data.primaryFormatter || formatNumber,
            area: data.primaryType === 'line',
            onClick: state.marketTab === 'city' ? (params) => drillToCity(params?.name) : undefined,
        });
        renderSeriesChart('market-secondary', secondary, { type: data.secondaryType, horizontal: data.secondaryType === 'bar', valueLabel: data.secondaryValue || '数值', tableFormatter: data.secondaryFormatter || formatNumber, axisFormatter: data.secondaryFormatter === formatCurrency ? (value) => `¥${value}` : undefined });
        const priceDistribution = normalizeSeries(
            pick(state.market, ['prices', 'priceDistribution', 'priceRanges', 'charts.prices'], []),
            ['range', 'bucket', 'name', 'label'],
            ['count', 'concertCount', 'value'],
        );
        const priceTotal = priceDistribution.reduce((sum, item) => sum + item.value, 0);
        const unknownPrice = priceDistribution
            .filter((item) => String(item.rawName).toLowerCase() === 'unknown')
            .reduce((sum, item) => sum + item.value, 0);
        const knownPrice = Math.max(0, priceTotal - unknownPrice);
        const priceCoverage = priceTotal
            ? `可解析票价 ${formatInteger(knownPrice)}/${formatInteger(priceTotal)} 场（${formatRatio(knownPrice / priceTotal)}）`
            : '暂无可解析票价样本';
        setSummary(
            'market-primary-summary',
            state.marketTab === 'price'
                ? `${priceCoverage}；未解析票价单独列示，不参与区间和均价判断。`
                : summaryForSeries(data.primary, '项', data.primaryFormatter || formatNumber),
        );
        if (data.secondaryFormatter === formatCurrency && data.secondary.length) {
            const highest = [...data.secondary].sort((a, b) => b.value - a.value)[0];
            const cityPriceSamples = data.secondary.reduce(
                (sum, item) => sum + numberValue(pick(item.raw, ['sampleCount'], 0)),
                0,
            );
            setSummary(
                'market-secondary-summary',
                `${highest.name}平均票价最高，为 ${formatCurrency(highest.value)}；覆盖 ${data.secondary.length} 个城市，`
                    + `仅基于 ${formatInteger(cityPriceSamples || knownPrice)} 场有可解析票价的演唱会。`,
            );
        } else {
            setSummary(
                'market-secondary-summary',
                summaryForSeries(data.secondary, '项', data.secondaryFormatter || formatNumber),
            );
        }
    };

    const loadMarket = async (force = false) => {
        if (state.loaded.has('market') && !force) return renderMarket();
        clearViewAlert('market');
        setChartState('market-primary', 'loading', '正在读取市场数据');
        setChartState('market-secondary', 'loading', '正在读取市场数据');
        try {
            state.market = await requestJSON(endpoints.market);
            renderMarket();
            state.loaded.add('market');
            setSyncNote(`更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())}`);
        } catch (error) {
            setViewAlert('market', error);
            setChartState('market-primary', 'error', '市场数据读取失败', 'market');
            setChartState('market-secondary', 'error', '市场数据读取失败', 'market');
        }
    };

    const sentimentSeries = (payload, tab) => {
        if (tab === 'topics') {
            return normalizeSeries(pick(payload, ['topics', 'negativeTopics', 'commentTopics', 'charts.topics'], []), ['topic', 'name', 'label'], ['sampleSize', 'count', 'frequency', 'value']);
        }
        if (tab === 'platforms') {
            return normalizeSeries(pick(payload, ['bySource', 'platforms', 'platformDistribution', 'sources', 'charts.platforms'], []), ['platform', 'source', 'name', 'label'], ['sampleSize', 'commentCount', 'count', 'value']);
        }
        const raw = pick(payload, ['distribution', 'sentimentDistribution', 'buckets', 'charts.sentiment'], []);
        const normalized = normalizeSeries(raw, ['range', 'bucket', 'label', 'name'], ['count', 'value', 'sampleCount']);
        const aliases = [
            ['极负面', '[-1,-0.6)', '-1~-0.6'],
            ['负面', '[-0.6,-0.2)', '-0.6~-0.2'],
            ['中性', '[-0.2,0.2)', '-0.2~0.2'],
            ['正面', '[0.2,0.6)', '0.2~0.6'],
            ['极正面', '[0.6,1]', '0.6~1'],
        ];
        if (normalized.length === 5) return normalized.map((item, index) => ({ ...item, name: aliases[index][0] }));
        return normalized;
    };

    const renderSentimentRisks = (risks) => {
        const target = $('#sentiment-risk-list');
        if (!target) return;
        target.innerHTML = risks.length ? risks.map((risk) => `<button class="risk-entry risk-entry-action" type="button" data-risk-name="${escapeHTML(risk.name)}" title="查看该对象的负面评论"><strong>${escapeHTML(risk.name)}</strong><span class="status-pill ${riskStatusClass(risk)}">${escapeHTML(riskLevelLabel(risk))} ${escapeHTML(risk.negativeRate !== null ? formatRatio(risk.negativeRate) : formatSentiment(risk.score))}</span><p>${escapeHTML(risk.topic)} · ${formatInteger(risk.samples)} 条样本</p><small>批次 ${escapeHTML(risk.batchId)} · ${escapeHTML(risk.rule)}</small></button>`).join('') : '<div class="empty-state">当前批次未识别到重点预警或关注对象</div>';
    };

    /** 主题 / 地区维度中，规则已判定评论的负面占比。点柱子可直接下钻到该主题或地区的原始评论。 */
    const renderNegativeBreakdowns = (payload) => {
        const toItems = (raw) => raw.map((item) => {
            const rawLabel = String(pick(item, ['label', 'name'], ''));
            return {
                name: displayLabel(rawLabel || '未分类'),
                rawLabel,
                value: Math.round(numberValue(pick(item, ['negativeRatio', 'negativeRate'], 0)) * 1000) / 10,
            };
        }).filter((item) => Number.isFinite(item.value));

        const drillInto = (params, items, field, suffix) => {
            const hit = items.find((item) => item.name === params.name);
            if (!hit || !hit.rawLabel) return;
            openCommentDrawer(
                { [field]: hit.rawLabel, sentiment: 'NEGATIVE' },
                `${hit.name} · ${suffix}`,
            );
        };

        const topics = toItems(arrayAt(payload, ['topicRisks']));
        renderSeriesChart('topic-risk', topics, {
            type: 'bar',
            horizontal: true,
            valueLabel: '负面率(%)',
            axisFormatter: (value) => `${value}%`,
            colorKeys: ['negative'],
            emptyMessage: '当前条件下暂无主题数据',
            tableFormatter: (item) => `${item.value}%`,
            onClick: (params) => drillInto(params, topics, 'topic', '负面评论'),
        });
        setSummary('topic-risk-summary', topics.length
            ? `共 ${topics.length} 个主题；数值为该主题已判定评论中负面评论的占比。点击柱条可查看对应的原始评论。`
            : '当前条件下暂无主题数据。');

        const regions = toItems(arrayAt(payload, ['regionRisks'])).slice(0, 10);
        renderSeriesChart('region-risk', regions, {
            type: 'bar',
            horizontal: true,
            valueLabel: '负面率(%)',
            axisFormatter: (value) => `${value}%`,
            colorKeys: ['negative'],
            emptyMessage: '当前条件下暂无地区数据',
            tableFormatter: (item) => `${item.value}%`,
            onClick: (params) => drillInto(params, regions, 'region', '负面评论'),
        });
        setSummary('region-risk-summary', regions.length
            ? `按评论用户所在地区统计，样本量 ≥ 200 条的省份参与排名，取负面占比最高的 ${regions.length} 个。点击柱条可查看该地区的原始评论。`
            : '当前条件下暂无地区数据。');
    };

    const renderSentiment = () => {
        if (!state.sentiment) return;
        const payload = state.sentiment;
        const metrics = pick(payload, ['metrics', 'summary'], payload);
        const risks = arrayAt(payload, ['risks', 'riskItems', 'riskAlerts', 'historicalRisks']).map(normalizeRisk);
        const alertCount = risks.filter((risk) => risk.level === 'ALERT').length;
        const watchCount = risks.filter((risk) => risk.level === 'WATCH').length;
        const scoredSamples = maybeNumber(pick(metrics, ['totalSamples', 'sampleCount', 'samples'], null));
        const totalComments = maybeNumber(pick(metrics, ['totalComments', 'commentCount', 'total'], null));
        $('#sentiment-samples').textContent = formatInteger(scoredSamples);
        $('#sentiment-coverage').textContent = scoredSamples !== null && totalComments
            ? `规则覆盖率 ${formatNumber(scoredSamples * 100 / totalComments)}% · 全部 ${formatInteger(totalComments)} 条`
            : '规则覆盖率 --';
        $('#sentiment-average').textContent = formatSentiment(pick(metrics, ['averageScore', 'avgSentiment', 'sentimentScore', 'average'], null));
        $('#sentiment-risks').textContent = `${formatInteger(alertCount)} / ${formatInteger(watchCount)}`;
        renderSentimentRisks(risks);
        renderNegativeBreakdowns(payload);
        const badge = $('#risk-nav-count');
        const dashboardMetrics = state.dashboard ? pick(state.dashboard, ['metrics', 'summary', 'overview'], state.dashboard) : null;
        const dashboardRiskCount = dashboardMetrics
            ? maybeNumber(pick(dashboardMetrics, ['activeRisks', 'riskCount', 'risks', 'riskObjects'], null))
            : null;
        if (badge && dashboardRiskCount !== null) {
            badge.textContent = formatInteger(dashboardRiskCount);
            badge.hidden = dashboardRiskCount === 0;
        }

        const items = sentimentSeries(payload, state.sentimentTab);
        const title = state.sentimentTab === 'distribution' ? '情感五档分布' : state.sentimentTab === 'topics' ? '评论主题分布' : '评论来源构成';
        $('#sentiment-chart-title').textContent = title;
        renderSeriesChart('sentiment-main', items, {
            type: state.sentimentTab === 'platforms' ? 'pie' : 'bar',
            horizontal: state.sentimentTab === 'topics',
            valueLabel: '样本数',
            colorKeys: state.sentimentTab === 'distribution' ? ['negative', 'warning', 'neutral', 'primary', 'positive'] : undefined,
        });
        const prefix = state.sentimentTab === 'distribution' ? '仅统计词典命中的已判定评论，未命中不计为中性；情感分范围为 -1 至 1。' : '';
        setSummary('sentiment-main-summary', `${prefix}${summaryForSeries(items, '条样本', formatInteger)}`);
    };

    const loadSentiment = async (force = false) => {
        if (state.loaded.has('sentiment') && !force) return renderSentiment();
        clearViewAlert('sentiment');
        setChartState('sentiment-main', 'loading', '正在读取舆情数据');
        try {
            state.sentiment = await requestJSON(endpoints.sentiment, queryFromForm($('#sentiment-filter-form')));
            const platforms = normalizeSeries(pick(state.sentiment, ['bySource', 'platforms', 'platformDistribution'], []), ['platform', 'source', 'name', 'label'], ['sampleSize', 'count', 'value']).map((item) => item.rawName);
            state.sentimentPlatforms = [...new Set([...state.sentimentPlatforms, ...platforms])];
            applyOptions({
                cities: [],
                statuses: [],
                categories: [],
                artists: state.filters?.artists || [],
                regions: state.filters?.regions || [],
                platforms: state.sentimentPlatforms,
            }, ['sentiment']);
            renderSentiment();
            state.loaded.add('sentiment');
            setSyncNote(`更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())}`);
        } catch (error) {
            setViewAlert('sentiment', error);
            setChartState('sentiment-main', 'error', '舆情数据读取失败', 'sentiment');
            renderSentimentRisks([]);
        }
    };

    const normalizeRecommendation = (item) => {
        const reasons = pick(item, ['reasons', 'matchedRules', 'explanations', 'reason'], []);
        return {
            concert: normalizeConcert(pick(item, ['concert', 'item'], item)),
            score: pick(item, ['score', 'recommendationScore', 'matchScore'], null),
            reasons: Array.isArray(reasons) ? reasons.map(String) : String(reasons || '').split(/[;,；，]/).map((value) => value.trim()).filter(Boolean),
        };
    };

    const renderRecommendations = (payload) => {
        const items = (Array.isArray(payload) ? payload : arrayAt(payload, ['items', 'recommendations', 'content', 'results'])).map(normalizeRecommendation);
        const target = $('#recommendation-list');
        target.setAttribute('aria-busy', 'false');
        $('#recommendation-summary').textContent = items.length ? `找到 ${items.length} 个推荐结果` : '当前条件下暂无推荐结果';
        if (!items.length) {
            target.innerHTML = '<div class="empty-state">调整城市、预算或艺人条件后重新生成推荐</div>';
            return;
        }
        target.innerHTML = items.map(({ concert, score, reasons }) => `<article class="recommendation-card"><div class="recommendation-card-head"><h2>${escapeHTML(concert.name)}</h2><span class="match-score">${score === null ? '--' : escapeHTML(formatNumber(score))}</span></div><div class="recommendation-facts"><span><i data-lucide="map-pin"></i><em>${escapeHTML(concert.city)} · ${escapeHTML(concert.venue)}</em></span><span><i data-lucide="calendar"></i><em>${escapeHTML(formatDate(concert.showTime))}</em></span><span><i data-lucide="ticket"></i><em>${escapeHTML(formatCurrency(concert.minPrice))} 起 · ${escapeHTML(displayLabel(concert.status))}</em></span></div><div class="reason-list">${(reasons.length ? reasons : ['规则依据待补充']).slice(0, 4).map((reason) => `<span>${escapeHTML(reason)}</span>`).join('')}</div><button class="button button-secondary" type="button" data-concert-detail="${escapeHTML(concert.id)}"><i data-lucide="panel-right-open"></i><span>查看详情</span></button></article>`).join('');
        refreshIcons();
    };

    const loadRecommendations = async (force = false) => {
        if (state.loaded.has('recommendations') && !force) return;
        clearViewAlert('recommendations');
        const target = $('#recommendation-list');
        target.setAttribute('aria-busy', 'true');
        target.innerHTML = '<div class="loading-block"><span class="spinner" aria-hidden="true"></span><span>正在读取推荐</span></div>';
        try {
            const params = queryFromForm($('#recommendation-form'));
            const payload = await requestJSON(endpoints.recommendations, params);
            renderRecommendations(payload);
            applyOptions(extractOptions(payload));
            state.loaded.add('recommendations');
            setSyncNote(`更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())}`);
        } catch (error) {
            setViewAlert('recommendations', error);
            target.setAttribute('aria-busy', 'false');
            target.innerHTML = `<div class="empty-state">${escapeHTML(error.message)}</div>`;
        }
    };

    const renderQuality = (payload) => {
        state.quality = payload;
        const metrics = pick(payload, ['totals', 'metrics', 'summary', 'quality'], payload);
        const total = pick(metrics, ['inputCount', 'total', 'totalRecords', 'recordCount'], null);
        const missing = pick(metrics, ['missing', 'missingCount', 'missingRecords'], null);
        const duplicates = pick(metrics, ['duplicates', 'duplicateCount', 'duplicateRecords'], null);
        const sources = normalizeSeries(pick(payload, ['sources', 'sourceCoverage', 'sourceDistribution', 'charts.sources'], []), ['source', 'sourceType', 'platform', 'name'], ['records', 'recordCount', 'count', 'value']);
        const values = { total, missing, duplicates, sources: pick(metrics, ['sourceCount', 'sources'], sources.length) };
        Object.entries(values).forEach(([key, value]) => {
            const node = $(`[data-quality-metric="${key}"]`);
            if (node) node.textContent = formatInteger(value);
        });
        const totalValue = numberValue(total);
        const missingRate = totalValue ? numberValue(missing) / totalValue : pick(metrics, ['missingRate'], null);
        const duplicateRate = totalValue ? numberValue(duplicates) / totalValue : pick(metrics, ['duplicateRate'], null);
        $('[data-quality-rate="missing"]').textContent = missingRate === null ? '比例待计算' : `${formatRatio(missingRate)} 占比`;
        $('[data-quality-rate="duplicates"]').textContent = duplicateRate === null ? '比例待计算' : `${formatRatio(duplicateRate)} 占比`;

        renderSeriesChart('quality-sources', sources, { type: 'pie', valueLabel: '记录数', tableFormatter: formatInteger });
        setSummary('quality-sources-summary', summaryForSeries(sources, '条记录', formatInteger));

        const metricLabels = {
            ACCEPTED_RECORDS: '接收记录',
            COMPLETENESS_RATE: '完整率',
            DUPLICATE_RECORDS: '重复记录',
            MISSING_ARTIST: '艺人缺失',
            MISSING_CITY: '城市缺失',
            MISSING_COLLECTED_AT: '采集时间缺失',
            MISSING_COMMENT_TIME: '评论时间缺失',
            MISSING_PRICE: '票价缺失',
            MISSING_REQUIRED: '必填字段缺失',
            MISSING_SOURCE_URL: '来源链接缺失',
            MISSING_USER_REGION: '用户地区缺失',
            MISSING_VENUE: '场馆缺失',
            INVALID_SENTIMENT: '无效情感分',
            IMPORTED_SENTIMENT_IGNORED: '已忽略外部情感分',
            LEXICON_SENTIMENT: '词典情感计算',
            LEXICON_UNMATCHED: '词典未命中',
            SOURCE_COVERAGE: '来源覆盖',
            UNKNOWN_SOURCE: '未知来源',
        };
        const sourceMetrics = arrayAt(payload, ['sourceMetrics', 'qualityBySource', 'metricsBySource']);
        const sourceMetricsBody = $('#quality-source-metrics-body');
        if (sourceMetricsBody) {
            sourceMetricsBody.innerHTML = sourceMetrics.length ? sourceMetrics.map((metric) => {
                const source = displayLabel(pick(metric, ['sourceType', 'source', 'platform'], '来源待定'));
                const metricKey = String(pick(metric, ['metricKey', 'key', 'metric'], 'UNKNOWN'));
                const metricValue = pick(metric, ['metricValue', 'value'], null);
                const records = pick(metric, ['recordCount', 'records', 'count'], null);
                const missingCount = pick(metric, ['missingCount', 'missing', 'missingRecords'], null);
                const duplicateCount = pick(metric, ['duplicateCount', 'duplicates', 'duplicateRecords'], null);
                const value = metricKey.endsWith('_RATE') ? formatRatio(metricValue) : formatNumber(metricValue);
                return `<tr><td><strong>${escapeHTML(source)}</strong></td><td>${escapeHTML(metricLabels[metricKey] || displayLabel(metricKey))}</td><td>${escapeHTML(value)}</td><td>${escapeHTML(formatInteger(records))}</td><td>${escapeHTML(formatInteger(missingCount))}</td><td>${escapeHTML(formatInteger(duplicateCount))}</td></tr>`;
            }).join('') : '<tr><td colspan="6"><div class="table-state">暂无逐来源质量指标</div></td></tr>';
        }

        const batches = arrayAt(payload, ['batches', 'recentBatches', 'crawlerBatches', 'batchRecords']);
        const target = $('#quality-batch-list');
        target.innerHTML = batches.length ? batches.slice(0, 8).map((batch) => {
            const status = displayLabel(pick(batch, ['status', 'state'], '状态待定'));
            const id = String(pick(batch, ['batchId', 'id', 'name'], '--'));
            const count = pick(batch, ['successCount', 'processedCount', 'recordCount', 'count'], null);
            const time = pick(batch, ['finishedAt', 'processedAt', 'collectedAt', 'createdAt'], null);
            const source = displayLabel(pick(batch, ['sourceType', 'source', 'jobType'], '数据批次'));
            return `<div class="batch-row"><strong>${escapeHTML(id)}</strong><span class="status-pill ${statusClass(status)}">${escapeHTML(status)}</span><p>${escapeHTML(source)} · ${formatInteger(count)} 条 · ${escapeHTML(formatDateTime(time))}</p></div>`;
        }).join('') : '<div class="empty-state">暂无数据批次记录</div>';
        const version = pick(payload, ['datasetVersion', 'latestBatch.batchId'], null);
        setDatasetVersion(version);
    };

    const loadQuality = async (force = false) => {
        if (state.loaded.has('quality') && !force) return renderQuality(state.quality);
        clearViewAlert('quality');
        setChartState('quality-sources', 'loading', '正在读取质量数据');
        try {
            const payload = await requestJSON(endpoints.quality);
            renderQuality(payload);
            state.loaded.add('quality');
            setSyncNote(`更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())}`);
        } catch (error) {
            setViewAlert('quality', error);
            setChartState('quality-sources', 'error', '质量数据读取失败', 'quality');
            $('#quality-batch-list').innerHTML = '<div class="empty-state">批次记录读取失败</div>';
        }
    };

    const normalizeJob = (item) => ({
        id: pick(item, ['id', 'jobId', 'runId'], ''),
        batchId: String(pick(item, ['batchId', 'batch'], '')),
        type: displayLabel(pick(item, ['jobType', 'type', 'name'], '未命名任务')),
        engine: String(pick(item, ['executionEngine', 'engine'], '')),
        status: displayLabel(pick(item, ['status', 'state'], '状态待定')),
        processed: pick(item, ['processedCount', 'successCount', 'outputCount', 'recordCount'], null),
        total: pick(item, ['inputCount', 'totalCount'], null),
        succeeded: pick(item, ['successCount', 'processedCount'], null),
        failed: pick(item, ['failedCount', 'failureCount'], null),
        summary: String(pick(item, ['workerSummary', 'summary'], '')),
        message: String(pick(item, ['message', 'detail'], '')),
        duration: pick(item, ['durationMs', 'elapsedMs'], null) ?? (pick(item, ['durationSeconds'], null) !== null ? numberValue(pick(item, ['durationSeconds'], 0)) * 1000 : pick(item, ['duration'], null)),
        startedAt: pick(item, ['startedAt', 'startTime', 'createdAt'], null),
        finishedAt: pick(item, ['finishedAt', 'endTime', 'completedAt'], null),
    });

    const renderJobs = (payload) => {
        const jobs = (Array.isArray(payload) ? payload : arrayAt(payload, ['items', 'jobs', 'content', 'records', 'results'])).map(normalizeJob);
        const apiPage = pick(payload, ['page', 'number', 'pageNumber', 'pagination.page'], null);
        if (apiPage !== null) state.jobPage = numberValue(apiPage) + (getPath(payload, 'page') !== undefined || getPath(payload, 'number') !== undefined ? 1 : 0);
        const total = numberValue(pick(payload, ['totalElements', 'total', 'totalCount', 'pagination.total'], jobs.length));
        state.jobPages = Math.max(1, numberValue(pick(payload, ['totalPages', 'pages', 'pagination.totalPages'], Math.ceil(total / 12) || 1), 1));
        $('#job-result-count').textContent = formatInteger(total);
        $('#job-page-label').textContent = `第 ${state.jobPage} / ${state.jobPages} 页`;
        $('[data-job-page="prev"]').disabled = state.jobPage <= 1;
        $('[data-job-page="next"]').disabled = state.jobPage >= state.jobPages;
        const body = $('#jobs-table-body');
        body.innerHTML = jobs.length ? jobs.map((job) => `
            <tr><td><div class="cell-stack"><strong>${escapeHTML(job.type)}</strong><small title="${escapeHTML(job.message || job.summary || '')}">${escapeHTML(job.summary || job.batchId || (job.id ? `#${job.id}` : '批次待定'))}</small></div></td><td><span class="status-pill ${statusClass(job.status)}">${escapeHTML(job.status)}</span><small>${escapeHTML(job.engine || '')}</small></td><td>${escapeHTML(formatInteger(job.processed))}${job.total !== null ? ` / ${escapeHTML(formatInteger(job.total))}` : ''}</td><td>${escapeHTML(formatDuration(job.duration))}</td><td>${escapeHTML(formatDateTime(job.startedAt))}</td><td>${escapeHTML(formatDateTime(job.finishedAt))}</td></tr>`).join('') : '<tr><td colspan="6"><div class="empty-state">暂无任务运行记录</div></td></tr>';
        refreshIcons();
    };

    const loadJobs = async (force = false) => {
        if (state.loaded.has('jobs') && !force) return;
        clearViewAlert('jobs');
        $('#jobs-table-body').innerHTML = '<tr><td colspan="6"><div class="table-state"><span class="spinner" aria-hidden="true"></span>正在读取任务</div></td></tr>';
        const params = new URLSearchParams({ page: String(Math.max(0, state.jobPage - 1)), size: '12' });
        try {
            const payload = await requestJSON(endpoints.jobs, params);
            renderJobs(payload);
            state.loaded.add('jobs');
            setSyncNote(`更新于 ${new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit' }).format(new Date())}`);
        } catch (error) {
            setViewAlert('jobs', error);
            $('#jobs-table-body').innerHTML = `<tr><td colspan="6"><div class="empty-state">${escapeHTML(error.message)}</div></td></tr>`;
        }
    };

    /** 市场页四个标签页与导出维度的对应关系。 */
    const MARKET_EXPORT_DIMENSIONS = { city: 'cities', trend: 'trend', price: 'prices', artist: 'artists' };

    const responseFilename = (response) => {
        const header = response.headers.get('Content-Disposition') || '';
        const match = /filename\*=UTF-8''([^;]+)/i.exec(header);
        if (!match) return 'export.csv';
        try { return decodeURIComponent(match[1]); } catch (_) { return 'export.csv'; }
    };

    const saveBlob = (blob, filename) => {
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = filename;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 2000);
    };

    const runExport = async (kind, button) => {
        let url;
        let label;
        if (kind === 'market') {
            const dimension = MARKET_EXPORT_DIMENSIONS[state.marketTab] || 'artists';
            url = `${endpoints.exportMarket}?dimension=${encodeURIComponent(dimension)}`;
            label = '市场榜单';
        } else if (kind === 'risks') {
            url = endpoints.exportRisks;
            label = '预警名单';
        } else {
            const params = new URLSearchParams();
            Object.entries(commentView.params || {}).forEach(([key, value]) => {
                if (value !== null && value !== undefined && value !== '') params.set(key, value);
            });
            params.set('limit', '5000');
            url = `${endpoints.exportComments}?${params.toString()}`;
            label = '评论检索结果';
        }
        if (button) button.disabled = true;
        try {
            const response = await fetch(url, { headers: { Accept: 'text/csv', ...auth.headers() } });
            if (!response.ok) throw new Error(`导出失败（${response.status}）`);
            saveBlob(await response.blob(), responseFilename(response));
            announce(`已导出${label}`);
        } catch (error) {
            announce(error.message || '导出失败', 'error');
        } finally {
            if (button) button.disabled = false;
        }
    };

    const OPERATION_ACTIONS = {
        EXPORT_MARKET: '导出市场榜单',
        EXPORT_RISKS: '导出预警名单',
        EXPORT_COMMENTS: '导出评论',
        CLEAR_CACHE: '清理缓存',
    };

    const renderOperationLogs = (payload) => {
        const rows = Array.isArray(payload) ? payload : arrayAt(payload, ['items', 'content', 'records']);
        const total = numberValue(pick(payload, ['totalElements', 'total'], rows.length));
        const pages = Math.max(1, numberValue(pick(payload, ['totalPages', 'pages'], 1), 1));
        const current = numberValue(pick(payload, ['page', 'number'], 0)) + 1;
        state.logPages = pages;
        state.logPage = current;
        $('#log-result-count').textContent = formatInteger(total);
        $('#log-page-label').textContent = `第 ${current} / ${pages} 页`;
        $('[data-log-page="prev"]').disabled = current <= 1;
        $('[data-log-page="next"]').disabled = current >= pages;
        const body = $('#logs-table-body');
        body.innerHTML = rows.length ? rows.map((row) => {
            const ok = pick(row, ['success'], true);
            const account = escapeHTML(pick(row, ['account'], ''));
            const role = escapeHTML(pick(row, ['role'], ''));
            const actionKey = pick(row, ['action'], '');
            const action = escapeHTML(OPERATION_ACTIONS[actionKey] || actionKey);
            return `<tr><td>${escapeHTML(formatDateTime(pick(row, ['occurredAt', 'time'])))}</td><td><div class="cell-stack"><strong>${account}</strong><small>${role}</small></div></td><td>${action}</td><td><div class="cell-stack"><span>${escapeHTML(pick(row, ['target'], ''))}</span><small>${escapeHTML(pick(row, ['detail'], ''))}</small></div></td><td><span class="status-pill ${statusClass(ok ? 'SUCCEEDED' : 'FAILED')}">${ok ? '成功' : '失败'}</span></td><td>${escapeHTML(pick(row, ['clientIp'], ''))}</td></tr>`;
        }).join('') : '<tr><td colspan="6"><div class="empty-state">暂无操作记录</div></td></tr>';
        refreshIcons();
    };

    const loadOperationLogs = async () => {
        const body = $('#logs-table-body');
        if (!body) return;
        if (!auth.isAdmin()) {
            body.innerHTML = '<tr><td colspan="6"><div class="empty-state">请使用管理员账号登录后查看操作日志</div></td></tr>';
            return;
        }
        body.innerHTML = '<tr><td colspan="6"><div class="table-state"><span class="spinner" aria-hidden="true"></span>正在读取操作日志</div></td></tr>';
        try {
            const params = new URLSearchParams({ page: String(Math.max(0, state.logPage - 1)), size: '15' });
            renderOperationLogs(await requestJSON(endpoints.adminLogs, params));
        } catch (error) {
            body.innerHTML = `<tr><td colspan="6"><div class="empty-state">${escapeHTML(error.message)}</div></td></tr>`;
        }
    };

    const loaders = {
        health: loadHealth,
        dashboard: loadDashboard,
        concertFilters: loadConcertFilters,
        concerts: loadConcerts,
        market: loadMarket,
        sentiment: loadSentiment,
        recommendations: loadRecommendations,
        quality: loadQuality,
        jobs: loadJobs,
    };

    const loadResource = async (resource, force = false) => {
        if (!loaders[resource]) return;
        if (state.pending.has(resource)) return state.pending.get(resource);
        const promise = loaders[resource](force).finally(() => state.pending.delete(resource));
        state.pending.set(resource, promise);
        return promise;
    };

    const loadRoute = (route, force = false) => Promise.allSettled(ROUTES[route].resources.map((resource) => loadResource(resource, force)));

    const showRoute = (route, focus = false) => {
        const next = ROUTES[route] ? route : 'overview';
        state.route = next;
        $$('[data-view]').forEach((view) => {
            const active = view.dataset.view === next;
            view.hidden = !active;
            view.classList.toggle('is-active', active);
        });
        $$('[data-route-link]').forEach((link) => {
            const active = link.dataset.routeLink === next;
            link.classList.toggle('is-active', active);
            if (active) link.setAttribute('aria-current', 'page');
            else link.removeAttribute('aria-current');
        });
        $('#route-title').textContent = ROUTES[next].title;
        closeSidebar(false);
        window.scrollTo(0, 0);
        loadRoute(next);
        window.requestAnimationFrame(() => {
            state.charts.forEach((chart) => chart.resize());
            if (focus) $('#main-content')?.focus({ preventScroll: true });
        });
    };

    const routeFromHash = () => window.location.hash.replace(/^#/, '').split('?')[0] || 'overview';

    const openSidebar = () => {
        $('#app-shell').classList.add('sidebar-open');
        $('[data-sidebar-close]', $('#sidebar'))?.focus();
    };

    const closeSidebar = (restoreFocus = true) => {
        const shell = $('#app-shell');
        if (!shell.classList.contains('sidebar-open')) return;
        shell.classList.remove('sidebar-open');
        if (restoreFocus) $('[data-sidebar-open]')?.focus();
    };

    const setTheme = (theme) => {
        document.documentElement.dataset.theme = theme;
        try { localStorage.setItem('app-shell-theme', theme); } catch (_) { /* Storage may be unavailable. */ }
        const themeColor = $('meta[name="theme-color"]');
        if (themeColor) themeColor.content = theme === 'dark' ? '#090e14' : '#f5f8f6';
        const button = $('[data-theme-toggle]');
        if (button) {
            button.innerHTML = `<i data-lucide="${theme === 'dark' ? 'sun' : 'moon'}"></i>`;
            button.setAttribute('aria-label', theme === 'dark' ? '切换到浅色主题' : '切换到深色主题');
            button.setAttribute('aria-pressed', String(theme === 'dark'));
            button.title = button.getAttribute('aria-label');
        }
        refreshIcons();
        window.requestAnimationFrame(rerenderCharts);
    };

    const refreshCurrentView = async () => {
        const button = $('[data-refresh-view]');
        button.disabled = true;
        button.setAttribute('aria-busy', 'true');
        try {
            await loadRoute(state.route, true);
            if (state.route === 'concerts' && state.concertMode === 'calendar') await loadCalendar();
            if (state.route === 'data' && state.dataTab === 'jobs') await loadResource('jobs', true);
            announce('当前视图已刷新');
        } finally {
            button.disabled = false;
            button.removeAttribute('aria-busy');
        }
    };

    const setConcertMode = (mode) => {
        state.concertMode = mode;
        $$('[data-concert-mode]').forEach((button) => {
            const active = button.dataset.concertMode === mode;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-pressed', String(active));
        });
        $$('[data-concert-view]').forEach((view) => { view.hidden = view.dataset.concertView !== mode; });
        if (mode === 'calendar') loadCalendar();
        else if (!state.loaded.has('concerts')) loadResource('concerts', true);
    };

    const setMarketTab = (tab) => {
        state.marketTab = tab;
        $$('[data-market-tab]').forEach((button) => {
            const active = button.dataset.marketTab === tab;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-selected', String(active));
        });
        renderMarket();
    };

    const setSentimentTab = (tab) => {
        state.sentimentTab = tab;
        $$('[data-sentiment-tab]').forEach((button) => {
            const active = button.dataset.sentimentTab === tab;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-selected', String(active));
        });
        renderSentiment();
    };

    const setDataTab = (tab) => {
        state.dataTab = tab;
        $$('[data-data-tab]').forEach((button) => {
            const active = button.dataset.dataTab === tab;
            button.classList.toggle('is-active', active);
            button.setAttribute('aria-selected', String(active));
        });
        $$('[data-data-pane]').forEach((pane) => {
            const active = pane.dataset.dataPane === tab;
            pane.hidden = !active;
            pane.classList.toggle('is-active', active);
        });
        if (tab === 'jobs') loadResource('jobs');
        if (tab === 'logs') loadOperationLogs();
        else window.requestAnimationFrame(() => state.charts.get('quality-sources')?.resize());
    };

    const toggleChartTable = (button) => {
        const key = button.dataset.chartTable;
        const canvas = $(`[data-chart="${key}"]`);
        const table = $(`[data-chart-table-view="${key}"]`);
        const showTable = button.getAttribute('aria-pressed') !== 'true';
        button.setAttribute('aria-pressed', String(showTable));
        button.setAttribute('aria-label', showTable ? '切换为图表' : '切换为数据表');
        button.title = showTable ? '切换为图表' : '切换为数据表';
        button.innerHTML = `<i data-lucide="${showTable ? 'chart-column' : 'table-2'}"></i>`;
        canvas.hidden = showTable;
        table.hidden = !showTable;
        const stored = state.chartData.get(key);
        const stateNode = $(`[data-chart-state="${key}"]`);
        if (showTable && stored?.items.length && stateNode) stateNode.hidden = true;
        if (!showTable && stored) renderSeriesChart(key, stored.items, stored.config);
        refreshIcons();
        if (!showTable) window.requestAnimationFrame(() => state.charts.get(key)?.resize());
    };

    const bindEvents = () => {
        window.addEventListener('hashchange', () => showRoute(routeFromHash(), true));
        window.addEventListener('resize', () => state.charts.forEach((chart) => chart.resize()), { passive: true });

        $('[data-sidebar-open]')?.addEventListener('click', openSidebar);
        $$('[data-sidebar-close]').forEach((button) => button.addEventListener('click', () => closeSidebar()));
        $('[data-theme-toggle]')?.addEventListener('click', () => setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'));
        $('[data-refresh-view]')?.addEventListener('click', refreshCurrentView);
        $('[data-global-search-open]')?.addEventListener('click', openGlobalSearch);
        $('[data-global-search-close]')?.addEventListener('click', () => closeGlobalSearch());
        $('[data-calendar-day-close]')?.addEventListener('click', () => closeCalendarDay());

        $('#global-search-form')?.addEventListener('submit', (event) => {
            event.preventDefault();
            runGlobalSearch();
        });
        $('#global-search-dialog')?.addEventListener('close', () => {
            if (state.globalSearchRestoreFocus && state.globalSearchOpener instanceof HTMLElement) state.globalSearchOpener.focus();
            state.globalSearchRestoreFocus = true;
        });
        $('#calendar-day-dialog')?.addEventListener('close', () => {
            if (state.calendarDialogRestoreFocus && state.calendarDialogOpener instanceof HTMLElement) state.calendarDialogOpener.focus();
            state.calendarDialogRestoreFocus = true;
        });

        $('[data-filter-toggle]')?.addEventListener('click', (event) => {
            const button = event.currentTarget;
            const panel = $('#concert-advanced-filters');
            const expanded = button.getAttribute('aria-expanded') === 'true';
            button.setAttribute('aria-expanded', String(!expanded));
            panel.hidden = expanded;
            button.innerHTML = `<i data-lucide="${expanded ? 'sliders-horizontal' : 'chevron-up'}"></i><span>${expanded ? '更多筛选' : '收起筛选'}</span>`;
            refreshIcons();
        });

        $('#concert-filter-form')?.addEventListener('submit', (event) => {
            event.preventDefault();
            state.concertPage = 1;
            state.loaded.delete('concerts');
            if (state.concertMode === 'calendar') loadCalendar();
            else loadResource('concerts', true);
        });
        $('#concert-filter-form')?.addEventListener('reset', () => window.setTimeout(() => {
            state.concertPage = 1;
            state.loaded.delete('concerts');
            if (state.concertMode === 'calendar') loadCalendar();
            else loadResource('concerts', true);
        }, 0));
        $('#sentiment-filter-form')?.addEventListener('submit', (event) => {
            event.preventDefault();
            state.loaded.delete('sentiment');
            loadResource('sentiment', true);
        });
        $('#recommendation-form')?.addEventListener('submit', (event) => {
            event.preventDefault();
            state.loaded.delete('recommendations');
            loadResource('recommendations', true);
        });
        $('#recommendation-form')?.addEventListener('reset', () => window.setTimeout(() => {
            state.loaded.delete('recommendations');
            loadResource('recommendations', true);
        }, 0));

        document.addEventListener('click', (event) => {
            const routeLink = event.target.closest('[data-route-link], .footer-top');
            if (routeLink) {
                const targetRoute = routeLink.dataset.routeLink || routeLink.getAttribute('href')?.replace(/^#/, '');
                if (targetRoute && targetRoute === routeFromHash()) {
                    event.preventDefault();
                    showRoute(targetRoute, true);
                }
            }

            const concertMode = event.target.closest('[data-concert-mode]');
            if (concertMode) setConcertMode(concertMode.dataset.concertMode);

            const pageButton = event.target.closest('[data-concert-page]');
            if (pageButton && !pageButton.disabled) {
                state.concertPage += pageButton.dataset.concertPage === 'next' ? 1 : -1;
                state.loaded.delete('concerts');
                loadResource('concerts', true);
            }

            const calendarButton = event.target.closest('[data-calendar]');
            if (calendarButton) {
                const delta = calendarButton.dataset.calendar === 'next' ? 1 : -1;
                state.calendarDate = new Date(state.calendarDate.getFullYear(), state.calendarDate.getMonth() + delta, 1);
                loadCalendar();
            }

            const calendarDay = event.target.closest('[data-calendar-day]');
            if (calendarDay) openCalendarDay(calendarDay.dataset.calendarDay);

            const searchConcert = event.target.closest('[data-search-concert]');
            if (searchConcert) {
                const focusOrigin = state.globalSearchOpener;
                closeGlobalSearch(false);
                loadConcertDetail(searchConcert.dataset.searchConcert, focusOrigin);
            }

            const calendarConcert = event.target.closest('[data-calendar-concert]');
            if (calendarConcert) {
                const focusOrigin = state.calendarDialogOpener;
                closeCalendarDay(false);
                loadConcertDetail(calendarConcert.dataset.calendarConcert, focusOrigin);
            }

            const marketTab = event.target.closest('[data-market-tab]');
            if (marketTab) setMarketTab(marketTab.dataset.marketTab);

            const sentimentTab = event.target.closest('[data-sentiment-tab]');
            if (sentimentTab) setSentimentTab(sentimentTab.dataset.sentimentTab);

            const dataTab = event.target.closest('[data-data-tab]');
            if (dataTab) setDataTab(dataTab.dataset.dataTab);

            const chartToggle = event.target.closest('[data-chart-table]');
            if (chartToggle) toggleChartTable(chartToggle);

            const detailButton = event.target.closest('[data-concert-detail]');
            if (detailButton) loadConcertDetail(detailButton.dataset.concertDetail);

            const closeButton = event.target.closest('[data-drawer-close]');
            if (closeButton && !closeButton.matches('.sidebar-scrim')) closeDrawer();

            const riskEntry = event.target.closest('[data-risk-name]');
            if (riskEntry) openCommentDrawer(
                { artist: riskEntry.dataset.riskName, sentiment: 'NEGATIVE' },
                `${riskEntry.dataset.riskName} · 负面评论`,
                riskEntry,
            );

            if (event.target.closest('[data-comment-close]')) closeCommentDrawer();

            const commentPage = event.target.closest('[data-comment-page]');
            if (commentPage && !commentPage.disabled) {
                loadComments(commentPage.dataset.commentPage === 'next'
                    ? commentView.page + 1
                    : Math.max(commentView.page - 1, 0));
            }

            if (event.target.closest('[data-comment-retry]')) loadComments(commentView.page);

            const retry = event.target.closest('[data-retry-resource]');
            if (retry) {
                const resource = retry.dataset.retryResource;
                state.loaded.delete(resource);
                loadResource(resource, true);
            }

            if (event.target.closest('[data-detail-retry]')) loadConcertDetail(state.detailId);

            const jobPage = event.target.closest('[data-job-page]');
            if (jobPage && !jobPage.disabled) {
                state.jobPage += jobPage.dataset.jobPage === 'next' ? 1 : -1;
                state.loaded.delete('jobs');
                loadResource('jobs', true);
            }

            const exportButton = event.target.closest('[data-export]');
            if (exportButton && !exportButton.disabled) runExport(exportButton.dataset.export, exportButton);

            const logPage = event.target.closest('[data-log-page]');
            if (logPage && !logPage.disabled) {
                state.logPage += logPage.dataset.logPage === 'next' ? 1 : -1;
                loadOperationLogs();
            }

            if (event.target.closest('[data-logout]')) {
                auth.clear();
                applyAuthState();
                announce('已退出登录');
                $('#logs-table-body').innerHTML = '<tr><td colspan="6"><div class="empty-state">请使用管理员账号登录后查看操作日志</div></td></tr>';
            }
        });

        document.addEventListener('keydown', (event) => {
            const drawer = $('#detail-drawer');
            if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
                event.preventDefault();
                if ($('#global-search-dialog')?.open) $('#global-search-input')?.focus();
                else if (!drawer.classList.contains('is-open')) openGlobalSearch();
                return;
            }
            const focusTrap = $('#comment-drawer')?.classList.contains('is-open')
                ? $('#comment-drawer')
                : (drawer.classList.contains('is-open') ? drawer : null);
            if (event.key === 'Tab' && focusTrap) {
                const focusable = $$('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex="-1"])', focusTrap).filter((node) => !node.hidden);
                if (focusable.length) {
                    const first = focusable[0];
                    const last = focusable[focusable.length - 1];
                    if (event.shiftKey && document.activeElement === first) {
                        event.preventDefault();
                        last.focus();
                    } else if (!event.shiftKey && document.activeElement === last) {
                        event.preventDefault();
                        first.focus();
                    }
                }
            }
            if (event.key !== 'Escape') return;
            const commentDrawer = $('#comment-drawer');
            if (commentDrawer?.classList.contains('is-open')) closeCommentDrawer();
            else if (drawer.classList.contains('is-open')) closeDrawer();
            else closeSidebar();
        });
    };

    const applyAuthState = () => {
        const current = auth.read();
        state.authUser = current ? current.user : null;
        const nameNode = $('#account-name');
        if (nameNode) {
            nameNode.textContent = current ? (current.user.displayName || '已登录') : '登录';
        }
        const chip = $('#account-chip');
        if (chip) {
            chip.href = current ? '#data' : '/login.html';
            chip.title = current ? `${current.user.username} · ${current.user.role}` : '登录账号';
            chip.dataset.loggedIn = current ? 'true' : 'false';
        }
        const logTab = $('#data-tab-logs');
        if (logTab) logTab.hidden = !auth.isAdmin();
        const logout = $('#logout-button');
        if (logout) logout.hidden = !current;
    };

    const initialize = () => {
        refreshIcons();
        setTheme(document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
        bindEvents();
        applyAuthState();
        const route = routeFromHash();
        if (!ROUTES[route]) history.replaceState(null, '', '#overview');
        showRoute(ROUTES[route] ? route : 'overview');
    };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize, { once: true });
    else initialize();
})();
