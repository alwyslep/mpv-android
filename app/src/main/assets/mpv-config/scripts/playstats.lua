-- ############################################################
-- ## ⛔ DEPRECATED / 비활성 (2026-06-01) — mpv.conf 에서 script= 주석됨.
-- ## 사유: 포크 앱(p1-launcher)이 시청 추적을 라이브러리 레이어에 네이티브 구현
-- ##   (`Progress`/`watchStatus`: position/duration prefs, 이어보기, 안봄/보는중/
-- ##   다봄 필터, 타일 진행률 바). 본 Lua 의 playstats.json(파일명 키)은 네이티브가
-- ##   안 읽고 dormant jav_library 만 먹이던 별도 스토어 → 중복. (B-58 에서 NAS 통합
-- ##   시 네이티브 prefs/hub 데이터를 권위로 사용 예정, 이 json 아님.)
-- ## 되살리기: mpv.conf 의 `#script=.../playstats.lua` 주석 해제. 본문은 보존.
-- ## SSOT: docs/player_meta_translation.md §1.6.
-- ############################################################
-- ============================================================
-- playstats.lua v1.0.1 — 재생 통계 (안전 버전)
-- ------------------------------------------------------------
-- v1.0.0 -> v1.0.1 변경:
--   - mp.observe_property("pause", "bool", ...) 제거
--     (일부 mpv 빌드에서 "bool" 타입 미지원 가능성)
--   - 이모지 4바이트 UTF-8 시퀀스 제거 (3바이트 이하만 사용)
--   - timer 내부에서 pause/time-pos 모두 폴링 (단일 진입점)
--
-- keys:
--   Ctrl+i        이 파일 통계 OSD
--   Ctrl+Shift+i  전체 누적 통계 OSD
--
-- 자동 동작:
--   - file-loaded: plays++, last=now, 카운트 시작
--   - 매 1초 폴링: pause=false & 시킹 아닌 정상 재생만 누적
--   - end-file/shutdown: 마지막 누적 디스크 저장
-- 저장: /storage/emulated/0/mpv/playstats.json
--   { "filename": { plays: N, watched_s: S, last: ts, code: "..." }, ... }
-- ============================================================

local utils = require 'mp.utils'

local DB_PATH = "/storage/emulated/0/mpv/playstats.json"
local SAVE_INTERVAL = 30
local SEEK_MAX = 5
local CODE_RE = "(%u%u+%-%d%d+)"

local db = {}
local current_key = nil
local last_t = nil
local accum_s = 0

local function load_db()
    local f = io.open(DB_PATH, "r")
    if not f then db = {}; return end
    local content = f:read("*all")
    f:close()
    local parsed = utils.parse_json(content or "{}")
    db = (type(parsed) == "table") and parsed or {}
end

local function save_db()
    local s = utils.format_json(db)
    if not s then return end
    local f = io.open(DB_PATH, "w")
    if not f then return end
    f:write(s)
    f:close()
end

local function currentKey()
    local p = mp.get_property("path")
    if not p or p == "" then return nil end
    return p:match("([^/\\]+)$")
end

local function fmt_dur(s)
    s = math.floor(s)
    local h = math.floor(s / 3600)
    local m = math.floor((s % 3600) / 60)
    local sec = s % 60
    if h > 0 then return string.format("%dh %dm %ds", h, m, sec) end
    if m > 0 then return string.format("%dm %ds", m, sec) end
    return string.format("%ds", sec)
end

local function flush_accum()
    if not current_key then return end
    if accum_s < 1 then return end
    local entry = db[current_key]
    if not entry then return end
    entry.watched_s = (entry.watched_s or 0) + math.floor(accum_s)
    entry.last = os.time()
    accum_s = 0
    save_db()
end

mp.register_event("file-loaded", function()
    flush_accum()

    current_key = currentKey()
    last_t = nil
    accum_s = 0
    if not current_key then return end

    if not db[current_key] then
        local code = current_key:upper():match(CODE_RE) or ""
        db[current_key] = { plays = 0, watched_s = 0, code = code }
    end
    db[current_key].plays = (db[current_key].plays or 0) + 1
    db[current_key].last = os.time()
    save_db()
end)

mp.register_event("end-file", function() flush_accum() end)
mp.register_event("shutdown", function() flush_accum() end)

mp.add_periodic_timer(1.0, function()
    if not current_key then return end

    local paused = mp.get_property_bool("pause", false)
    if paused then last_t = nil; return end

    local t = mp.get_property_number("time-pos")
    if not t then last_t = nil; return end

    if last_t then
        local dt = t - last_t
        if dt > 0 and dt < SEEK_MAX then
            accum_s = accum_s + dt
        end
    end
    last_t = t

    if accum_s >= SAVE_INTERVAL then
        flush_accum()
    end
end)

local function show_file()
    if not current_key then
        mp.osd_message("재생 중인 파일 없음", 2); return
    end
    local entry = db[current_key]
    if not entry then
        mp.osd_message("통계 없음", 2); return
    end
    local total = (entry.watched_s or 0) + accum_s
    local label = (entry.code and entry.code ~= "") and entry.code or current_key
    local lines = {
        "[stats] " .. label,
        string.format("재생 %d회", entry.plays or 0),
        string.format("누적 %s", fmt_dur(total)),
    }
    mp.osd_message(table.concat(lines, "\n"), 4)
end

local function show_total()
    local files, plays, watched = 0, 0, 0
    for _, e in pairs(db) do
        files = files + 1
        plays = plays + (e.plays or 0)
        watched = watched + (e.watched_s or 0)
    end
    watched = watched + accum_s
    local lines = {
        "[stats] 전체 시청 통계",
        string.format("작품: %d개", files),
        string.format("재생: %d회", plays),
        string.format("누적: %s", fmt_dur(watched)),
    }
    mp.osd_message(table.concat(lines, "\n"), 5)
end

load_db()

mp.register_script_message("playstats-file", show_file)
mp.register_script_message("playstats-total", show_total)

mp.commandv("script-message", "soul-register", "playstats", "1.0.1",
    utils.format_json({
        "Ctrl+i: 이 파일 통계 OSD",
        "Ctrl+Shift+i: 전체 누적 통계 OSD",
    }))

local cnt = 0
for _ in pairs(db) do cnt = cnt + 1 end
mp.msg.info("playstats.lua v1.0.1 loaded -- " .. cnt .. " files tracked")
