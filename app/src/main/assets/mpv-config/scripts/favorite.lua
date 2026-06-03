-- ############################################################
-- ## ⛔ DEPRECATED / 비활성 (2026-06-01) — mpv.conf 에서 script= 주석됨.
-- ## 사유: 포크 앱(alwyslep/mpv-android, p1-launcher)이 즐겨찾기를 라이브러리
-- ##   레이어에 네이티브 구현(`Favorites` obj, prefs `favorites_v1`·uri 키, 타일
-- ##   ♥뱃지, QuickSettings '즐겨찾기만' 필터, hub /review 연동). 본 Lua 는
-- ##   플레이어 레이어에서 favorites.json(파일명 키)에 별도 저장 → 네이티브와
-- ##   분리된 중복 스토어라 혼란. 네이티브가 '통합' 경로(품번→hub)라 그쪽으로 일원화.
-- ## 되살리기: mpv.conf 의 `#script=.../favorite.lua` 주석 해제. 본문은 보존.
-- ## SSOT: docs/player_meta_translation.md §1.6.
-- ############################################################
-- ============================================================
-- favorite.lua — 즐겨찾기 ⭐
-- ------------------------------------------------------------
-- keys:
--   Ctrl+f    별표 토글
--   F11       이 파일 별표 상태 + 전체 별표 개수 OSD
--
-- 저장: /storage/emulated/0/mpv/favorites.json
--   { "filename.mp4": { ts: 1234567890, code: "ABC-123" }, ... }
-- 키: 파일명만 (경로 무관 — NAS↔로컬 매칭)
-- ============================================================

local utils = require 'mp.utils'

local DB_PATH = "/storage/emulated/0/mpv/favorites.json"
local CODE_RE = "(%u%u+%-%d%d+)"

local db = {}

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
    if not f then
        mp.osd_message("⚠ 즐겨찾기 저장 실패", 2)
        return
    end
    f:write(s)
    f:close()
end

local function currentKey()
    local p = mp.get_property("path")
    if not p or p == "" then return nil end
    return p:match("([^/\\]+)$")
end

local function currentCode()
    local k = currentKey() or ""
    return k:upper():match(CODE_RE)
end

local function count()
    local n = 0
    for _ in pairs(db) do n = n + 1 end
    return n
end

local function toggle()
    local k = currentKey()
    if not k then return end
    if db[k] then
        db[k] = nil
        save_db()
        mp.osd_message(string.format("☆ 즐겨찾기 해제 (전체 %d개)", count()), 2)
    else
        db[k] = { ts = os.time(), code = currentCode() or "" }
        save_db()
        mp.osd_message(string.format("⭐ 즐겨찾기 추가 (전체 %d개)", count()), 2)
    end
end

local function show_status()
    local k = currentKey()
    if not k then return end
    if db[k] then
        mp.osd_message(string.format("⭐ 즐겨찾기됨 (전체 %d개)", count()), 3)
    else
        mp.osd_message(string.format("☆ 미즐겨찾기 (전체 %d개)", count()), 3)
    end
end

load_db()

mp.register_script_message("favorite-toggle", toggle)
mp.register_script_message("favorite-status", show_status)

-- 파일 로드 시 별표인 경우만 짧게 표시
mp.register_event("file-loaded", function()
    local k = currentKey()
    if k and db[k] then
        mp.add_timeout(0.5, function()
            mp.osd_message("⭐", 1)
        end)
    end
end)

-- soul_manager 등록
mp.commandv("script-message", "soul-register", "favorite", "1.0.0",
    utils.format_json({
        "Ctrl+f: 즐겨찾기 토글",
        "F11: 즐겨찾기 상태 OSD",
    }))

mp.msg.info("favorite.lua loaded — " .. count() .. " entries")
