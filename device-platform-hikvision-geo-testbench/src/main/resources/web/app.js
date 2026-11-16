(() => {
    const $ = (selector) => document.querySelector(selector);
    const state = { operation: "target_acquire", mode: "single", preview: null, previewTab: "p2", lastCount: 0, points: [], pendingPoint: null, directionMarkers: [], selectedDirection: "", busy: false, submitting: false, requestSignature: "", lastSelectedIndex: -1, p2Configured: false };
    const canvas = $("#geo-canvas");
    const ctx = canvas.getContext("2d");

    async function api(path, body) {
        const response = await fetch(path, {
            method: body === undefined ? "GET" : "POST",
            headers: body === undefined ? {} : { "Content-Type": "application/json" },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
        const value = await response.json();
        if (!response.ok) throw new Error(value.error || `HTTP ${response.status}`);
        return value;
    }

    function notice(message, error = false) {
        $("#loop-status").textContent = message;
        $("#loop-status").classList.toggle("error", error);
    }

    function options() {
        return {
            index: Number($("#request-select").value || 0),
            identifier: state.operation,
            longitude: Number(state.pendingPoint ? state.pendingPoint.longitude : $("#longitude").value),
            latitude: Number(state.pendingPoint ? state.pendingPoint.latitude : $("#latitude").value),
            altitude: Number(state.pendingPoint ? state.pendingPoint.altitude : $("#altitude").value)
        };
    }

    function loopOptions() {
        return Object.assign(options(), {
            mode: state.mode,
            radiusMeters: Number($("#radius").value),
            maxDurationSeconds: Number($("#duration").value)
        });
    }

    function directionOptions(direction) {
        return Object.assign(options(), {
            longitude: Number($("#longitude").value),
            latitude: Number($("#latitude").value),
            altitude: Number($("#altitude").value),
            direction,
            radiusMeters: Number($("#radius").value)
        });
    }

    function randomOnceOptions() {
        return Object.assign(options(), {
            longitude: Number($("#longitude").value),
            latitude: Number($("#latitude").value),
            altitude: Number($("#altitude").value),
            radiusMeters: Number($("#radius").value)
        });
    }

    function showPreview(tab = state.previewTab) {
        state.previewTab = tab;
        document.querySelectorAll("[data-preview]").forEach((button) => {
            button.classList.toggle("selected", button.dataset.preview === tab);
        });
        const output = $("#preview-output");
        if (!state.preview) {
            output.textContent = "导入请求并选择点位后生成";
        } else if (tab === "p2") {
            output.textContent = JSON.stringify(state.preview.p2Request, null, 2);
        } else {
            output.textContent = state.preview.deviceCommand[tab];
        }
    }

    function setMode(mode) {
        state.mode = mode;
        if (mode !== "single") {
            state.pendingPoint = null;
            state.selectedDirection = "";
            document.querySelectorAll("[data-random-direction]").forEach((item) => item.classList.remove("selected"));
        }
        document.querySelectorAll("[data-mode]").forEach((button) => {
            button.classList.toggle("selected", button.dataset.mode === mode);
        });
        $("#send-once").classList.toggle("hidden", mode !== "single");
        $("#send-random-once").classList.toggle("hidden", mode !== "single");
        $("#single-direction").classList.toggle("hidden", mode !== "single");
        $("#start-loop").classList.toggle("hidden", mode === "single");
        $("#start-loop").textContent = mode === "cardinal" ? "开始四方位序列" : "开始随机下发";
        draw();
    }

    function setOperation(identifier) {
        state.operation = identifier;
        document.querySelectorAll("[data-operation]").forEach((button) => {
            button.classList.toggle("selected", button.dataset.operation === identifier);
        });
        state.preview = null;
        showPreview();
    }

    function setBusy(running, inFlight = false) {
        inFlight = inFlight || state.submitting;
        state.busy = running || inFlight;
        const hasDestination = Boolean($("#p2-base").value.trim());
        $("#stop-loop").disabled = !running;
        $("#start-loop").disabled = running || inFlight || !$("#request-select").value || !hasDestination;
        $("#send-once").disabled = running || inFlight || !$("#request-select").value || !hasDestination;
        $("#send-random-once").disabled = running || inFlight || !$("#request-select").value || !hasDestination;
        document.querySelectorAll("[data-random-direction]").forEach((button) => {
            button.disabled = running || inFlight;
        });
        $("#preview").disabled = running || inFlight || !$("#request-select").value;
        $("#import-request").disabled = running || inFlight;
        $("#configure-p2").disabled = running || inFlight;
        $("#p2-base").disabled = running || inFlight;
    }

    function showPoint(point, label) {
        $("#latest-point").textContent = point
            ? `${label}: ${Number(point.latitude).toFixed(8)}, ${Number(point.longitude).toFixed(8)}, ${point.altitude} m`
            : "尚无下发点";
    }

    function renderState(value) {
        state.p2Configured = value.p2Configured;
        $("#request-count").textContent = value.requestCount ? `${value.requestCount} 条请求` : "未载入";
        const select = $("#request-select");
        const selected = value.selectedIndex;
        const signature = value.requests.map((request) => `${request.index}:${request.deviceId}:${request.identifier}`).join("|");
        const requestSetChanged = signature !== state.requestSignature;
        if (requestSetChanged) {
            select.replaceChildren();
            value.requests.forEach((request) => {
                const option = document.createElement("option");
                option.value = request.index;
                option.textContent = `#${request.index + 1} · ${request.identifier} · device ${request.deviceId}`;
                select.append(option);
            });
            state.requestSignature = signature;
        }
        select.disabled = value.requests.length === 0 || value.running || value.inFlight;
        if (value.requests.length) select.value = selected >= 0 ? String(selected) : "";
        const current = value.requests.find((request) => request.index === Number(select.value));
        if (current) {
            if (requestSetChanged || selected !== state.lastSelectedIndex) {
                state.pendingPoint = null;
                state.selectedDirection = "";
                document.querySelectorAll("[data-random-direction]").forEach((item) => item.classList.remove("selected"));
                setOperation(current.identifier);
                state.lastSelectedIndex = selected;
                if (value.lastPoint == null && state.lastCount === 0) {
                    $("#longitude").value = current.geo.longitude;
                    $("#latitude").value = current.geo.latitude;
                    $("#altitude").value = current.geo.altitude;
                    state.selectedDirection = "";
                }
            }
            $("#request-meta").textContent = `deviceId ${current.deviceId}  /  targetId ${current.targetId}`;
        }
        if (value.lastPoint && value.sentCount !== state.lastCount) {
            state.points.push(value.lastPoint);
            if (state.points.length > 300) state.points.shift();
        }
        state.lastCount = value.sentCount;
        $("#loop-count").textContent = `${value.sentCount} 次下发`;
        if (!state.pendingPoint) {
            $("#loop-status").textContent = value.loopMessage || "等待操作";
            $("#loop-status").classList.toggle("error", /失败|超时/.test(value.loopMessage || ""));
        }
        setBusy(value.running, value.inFlight);
        if (value.lastPoint) showPoint(value.lastPoint, "最近下发");
        if (value.lastResult) renderResult(value.lastResult);
        if (value.lastPreview && value.sentCount !== state.previewCount) {
            state.preview = value.lastPreview;
            state.previewCount = value.sentCount;
            showPreview("p2");
        }
        draw();
    }

    function renderResult(result) {
        const status = result.accepted ? `HTTP ${result.httpStatus} · P2 返回成功` :
            result.transportError ? `连接失败 · ${result.transportError}` : `HTTP ${result.httpStatus} · P2 返回失败`;
        $("#response-status").textContent = status;
        $("#response-status").classList.toggle("failed", !result.accepted);
        let body = result.responseBody || "无响应正文";
        try { body = JSON.stringify(JSON.parse(body), null, 2); } catch (_) { }
        $("#response-output").textContent = `X-Request-Id: ${result.requestId || "—"}\n\n${body}`;
    }

    function draw() {
        const width = canvas.clientWidth;
        const height = canvas.clientHeight;
        if (!width || !height) return;
        const dpr = Math.max(1, window.devicePixelRatio || 1);
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, width, height);
        ctx.fillStyle = "#f8faf9";
        ctx.fillRect(0, 0, width, height);

        const pad = 42;
        const cx = width / 2;
        const cy = height / 2;
        const radiusPx = Math.max(18, Math.min(width / 2 - pad, height / 2 - pad));
        ctx.strokeStyle = "#d8e1df";
        ctx.lineWidth = 1;
        for (let step = 1; step <= 3; step++) {
            ctx.beginPath();
            ctx.arc(cx, cy, radiusPx * step / 3, 0, Math.PI * 2);
            ctx.stroke();
        }
        ctx.beginPath(); ctx.moveTo(cx - radiusPx, cy); ctx.lineTo(cx + radiusPx, cy); ctx.stroke();
        ctx.beginPath(); ctx.moveTo(cx, cy - radiusPx); ctx.lineTo(cx, cy + radiusPx); ctx.stroke();
        ctx.beginPath(); ctx.arc(cx, cy, radiusPx, 0, Math.PI * 2);
        ctx.strokeStyle = "#087f82"; ctx.lineWidth = 2; ctx.stroke();

        const radius = Math.max(1, Number($("#radius").value) || 1000);
        $("#range-caption").textContent = `半径 ${radius} m · N ↑ / E →`;
        ctx.fillStyle = "#567078"; ctx.font = "12px system-ui"; ctx.textAlign = "center";
        ctx.fillText("N", cx, cy - radiusPx - 14);
        ctx.fillText("S", cx, cy + radiusPx + 23);
        ctx.fillText("W", cx - radiusPx - 18, cy + 4);
        ctx.fillText("E", cx + radiusPx + 18, cy + 4);
        ctx.fillText(`${Math.round(radius / 3)} m`, cx + radiusPx / 3, cy - 7);
        ctx.fillText(`${Math.round(radius)} m`, cx + radiusPx, cy + 18);

        const centerLat = Number($("#latitude").value);
        const centerLon = Number($("#longitude").value);
        const plot = (point, color, size) => {
            const north = Geo.north(point.latitude, centerLat);
            const east = Geo.east(point.longitude, centerLon, centerLat);
            const px = cx + east / radius * radiusPx;
            const py = cy - north / radius * radiusPx;
            ctx.beginPath(); ctx.arc(px, py, size, 0, Math.PI * 2); ctx.fillStyle = color; ctx.fill();
        };
        state.points.forEach((point, index) => plot(point, index === state.points.length - 1 ? "#d34d35" : "#c6a04b", index === state.points.length - 1 ? 6 : 3));
        if (state.pendingPoint) plot(state.pendingPoint, "#28764a", 7);
        state.directionMarkers = [
            { key: "N", label: "北", dx: 0, dy: -1 },
            { key: "NE", label: "东北", dx: Math.SQRT1_2, dy: -Math.SQRT1_2 },
            { key: "E", label: "东", dx: 1, dy: 0 },
            { key: "SE", label: "东南", dx: Math.SQRT1_2, dy: Math.SQRT1_2 },
            { key: "S", label: "南", dx: 0, dy: 1 },
            { key: "SW", label: "西南", dx: -Math.SQRT1_2, dy: Math.SQRT1_2 },
            { key: "W", label: "西", dx: -1, dy: 0 },
            { key: "NW", label: "西北", dx: -Math.SQRT1_2, dy: -Math.SQRT1_2 }
        ].map((direction) => Object.assign(direction, {
            x: cx + direction.dx * (radiusPx - 10),
            y: cy + direction.dy * (radiusPx - 10)
        }));
        state.directionMarkers.forEach((marker) => {
            ctx.beginPath();
            ctx.arc(marker.x, marker.y, marker.key === state.selectedDirection ? 8 : 6, 0, Math.PI * 2);
            ctx.fillStyle = marker.key === state.selectedDirection ? "#087f82" : "#da7b2d";
            ctx.fill();
            ctx.strokeStyle = "#fff";
            ctx.lineWidth = 2;
            ctx.stroke();
            ctx.fillStyle = "#1c3338";
            ctx.font = "11px system-ui";
            ctx.textAlign = marker.dx < -0.2 ? "right" : marker.dx > 0.2 ? "left" : "center";
            ctx.fillText(marker.label, marker.x + marker.dx * 10, marker.y + (marker.dy < -0.2 ? -10 : marker.dy > 0.2 ? 16 : 4));
        });
        ctx.beginPath(); ctx.arc(cx, cy, 6, 0, Math.PI * 2); ctx.fillStyle = "#087f82"; ctx.fill();
    }

    const Geo = {
        north(lat, centerLat) { return (Number(lat) - centerLat) * 111320; },
        east(lon, centerLon, lat) { return (Number(lon) - centerLon) * 111320 * Math.cos(lat * Math.PI / 180); }
    };

    async function refresh() {
        try {
            const value = await api("/api/state");
            renderState(value);
        } catch (error) {
            notice(error.message, true);
        }
    }

    function renderConfig(config) {
        $("#p2-base").value = config.p2BaseUrl;
        $("#connection").classList.toggle("ready", config.p2Configured);
        $("#connection").lastElementChild.textContent = config.p2Configured
            ? `下发地址：${config.p2BaseUrl}` : "未配置 P2";
        state.p2Configured = config.p2Configured;
        $("#auth-state").textContent = config.authorizationConfigured ? "Authorization 已由进程环境配置" : "Authorization 未配置";
    }

    async function syncP2Destination() {
        const config = await api("/api/config", { baseUrl: $("#p2-base").value.trim() });
        renderConfig(config);
        return config;
    }

    $("#p2-base").addEventListener("input", () => {
        $("#connection").classList.remove("ready");
        $("#connection").lastElementChild.textContent = "地址待应用，下发时自动同步";
    });

    $("#configure-p2").addEventListener("click", async () => {
        if (state.busy) return;
        state.submitting = true;
        setBusy(false, true);
        try {
            const config = await syncP2Destination();
            notice(config.p2Configured ? "P2 地址已保存在当前进程内存中。" : "P2 地址已清除。", false);
        } catch (error) { notice(error.message, true); }
        finally {
            state.submitting = false;
            setBusy(false, false);
        }
    });

    $("#import-request").addEventListener("click", async () => {
        try {
            state.points = [];
            state.lastCount = 0;
            state.previewCount = 0;
            state.requestSignature = "";
            state.lastSelectedIndex = -1;
            state.pendingPoint = null;
            state.selectedDirection = "";
            const value = await api("/api/import", { text: $("#request-input").value });
            renderState(value);
            state.preview = null;
            showPreview();
            notice(`已导入 ${value.requestCount} 条请求，尚未下发。`);
        } catch (error) { notice(error.message, true); }
    });

    $("#request-select").addEventListener("change", async (event) => {
        try {
            const value = await api("/api/select", { index: Number(event.target.value) });
            state.points = [];
            state.lastCount = 0;
            state.previewCount = 0;
            state.requestSignature = "";
            state.lastSelectedIndex = -1;
            state.pendingPoint = null;
            state.selectedDirection = "";
            renderState(value);
            state.preview = null;
            showPreview();
        } catch (error) { notice(error.message, true); }
    });

    document.querySelectorAll("[data-operation]").forEach((button) => button.addEventListener("click", () => {
        setOperation(button.dataset.operation);
    }));
    document.querySelectorAll("[data-mode]").forEach((button) => button.addEventListener("click", () => setMode(button.dataset.mode)));
    document.querySelectorAll("[data-preview]").forEach((button) => button.addEventListener("click", () => showPreview(button.dataset.preview)));

    $("#preview").addEventListener("click", async () => {
        try {
            state.preview = await api("/api/preview", options());
            showPreview();
            notice("已生成 P2 JSON、设备 XML 和 curl；尚未发送。", false);
        } catch (error) { notice(error.message, true); }
    });

    async function sendPoint(path, payload, label) {
        if (state.busy) return;
        state.submitting = true;
        setBusy(false, true);
        notice("正在等待 P2 响应…");
        try {
            const config = await syncP2Destination();
            if (!config.p2Configured) throw new Error("请填写 P2 服务地址。");
            const value = await api(path, payload);
            state.preview = { p2Request: value.request, deviceCommand: value.deviceCommand };
            state.pendingPoint = null;
            state.points.push(value.point);
            state.lastCount++;
            state.previewCount = state.lastCount;
            renderResult(value.result);
            showPoint(value.point, `${label}本次下发`);
            notice(value.result.accepted ? `${label}已下发到 P2；请观察摄像头实际响应。` : "P2 请求失败。", !value.result.accepted);
            showPreview("p2");
        } catch (error) { notice(error.message, true); }
        finally {
            state.submitting = false;
            setBusy(false, false);
        }
        await refresh();
    }

    $("#send-once").addEventListener("click", () => sendPoint("/api/send", options(), "指定点"));
    $("#send-random-once").addEventListener("click", () => sendPoint("/api/send-random", randomOnceOptions(), "随机点"));

    document.querySelectorAll("[data-random-direction]").forEach((button) => button.addEventListener("click", async () => {
        if (state.busy) return;
        const direction = button.dataset.randomDirection;
        try {
            const selected = await api("/api/random-cardinal-point", directionOptions(direction));
            state.pendingPoint = selected.point;
            state.selectedDirection = direction;
            document.querySelectorAll("[data-random-direction]").forEach((item) => {
                item.classList.toggle("selected", item.dataset.randomDirection === direction);
            });
            state.preview = $("#request-select").value ? await api("/api/preview", options()) : null;
            showPreview();
            showPoint(selected.point, `待下发·${button.textContent}向 / ${selected.distanceMeters.toFixed(2)} m`);
            notice(`已生成${button.textContent}向点位，距中心 ${selected.distanceMeters.toFixed(2)} m / 半径 ${selected.radiusMeters} m；点击“指定点下发”发送。`, false);
            draw();
        } catch (error) { notice(error.message, true); }
    }));

    $("#start-loop").addEventListener("click", async () => {
        if (state.busy) return;
        if (!$("#request-select").value) { notice("请先导入并选择一条 P1-1 请求。", true); return; }
        state.submitting = true;
        setBusy(false, true);
        try {
            const config = await syncP2Destination();
            if (!config.p2Configured) throw new Error("请填写 P2 服务地址。");
            const value = await api("/api/start", loopOptions());
            state.points = [];
            state.lastCount = 0;
            state.previewCount = 0;
            renderState(value);
            notice(state.mode === "cardinal" ? "四方位序列已启动。" : "圆内随机下发已启动。", false);
        } catch (error) { notice(error.message, true); }
        finally {
            state.submitting = false;
        }
        await refresh();
    });

    $("#stop-loop").addEventListener("click", async () => {
        try {
            const value = await api("/api/stop", {});
            renderState(value);
            notice(value.loopMessage, false);
        } catch (error) { notice(error.message, true); }
    });

    canvas.addEventListener("click", async (event) => {
        if (state.busy) return;
        const rect = canvas.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const marker = state.directionMarkers.find((point) => Math.hypot(point.x - x, point.y - y) <= 18);
        if (!marker) return;
        try {
            const selected = await api("/api/direction-point", directionOptions(marker.key));
            state.pendingPoint = selected.point;
            state.selectedDirection = marker.key;
            document.querySelectorAll("[data-random-direction]").forEach((item) => {
                item.classList.toggle("selected", item.dataset.randomDirection === marker.key);
            });
            state.preview = $("#request-select").value ? await api("/api/preview", options()) : null;
            showPreview();
            notice(`已选中${marker.label}方向目标；点击“指定点下发”发送。`, false);
            draw();
        } catch (error) { notice(error.message, true); }
    });

    ["longitude", "latitude", "altitude"].forEach((id) => $("#" + id).addEventListener("input", () => {
        state.selectedDirection = "";
        state.pendingPoint = null;
        document.querySelectorAll("[data-random-direction]").forEach((item) => item.classList.remove("selected"));
        draw();
    }));
    $("#radius").addEventListener("input", () => {
        state.selectedDirection = "";
        state.pendingPoint = null;
        document.querySelectorAll("[data-random-direction]").forEach((item) => item.classList.remove("selected"));
        draw();
    });
    window.addEventListener("resize", draw);
    window.setInterval(() => api("/api/heartbeat", {}).catch(() => {}), 5000);
    window.setInterval(refresh, 1000);

    api("/api/config").then(renderConfig).catch((error) => notice(error.message, true));
    refresh();
    draw();
})();
