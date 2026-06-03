-- ============================================================
-- _soul_manager.lua v1.0.2 — Soul Edition 통합 매니저
-- ------------------------------------------------------------
-- 파일명에 '_' 접두사 → mpv.conf 로드 순서상 가장 먼저.
-- 각 스크립트가 부팅 시 'soul-register' 메시지로 자기 정보 등록.
--
-- keys:
--   F10   로드된 스크립트 + 버전 OSD
--   F12   전체 단축키 cheat sheet OSD
--
-- v1.0.1: EXPECTED에 favorite, playstats 추가
-- v1.0.2 (2026-06-01): favorite, playstats 를 EXPECTED 에서 제거 — 포크 앱이
--   네이티브 구현해 mpv.conf 에서 비활성(중복/충돌). prev_next_file 도 비활성됐으나
--   원래 EXPECTED 밖(extra)이라 목록 변화 없음. 상세: player_meta_translation.md §1.6.
-- ============================================================

local utils = require 'mp.utils'

local EXPECTED = {
    "jav_osd",
    "precise_speed",
    "bookmarks",
    "sub_style_toggle",
    "multiwindow_fix",
    "screenshot_to_clip",
}

local registry = {}

mp.register_script_message("soul-register", function(name, version, keys_json)
    local keys = {}
    if keys_json and keys_json ~= "" then
        local parsed = utils.parse_json(keys_json)
        if type(parsed) == "table" then keys = parsed end
    end
    registry[name] = {
        version = version or "?",
        keys = keys,
        registered_at = mp.get_time(),
    }
    mp.msg.info(string.format("soul: registered %s v%s", name, version or "?"))
end)

local function is_expected(name)
    for _, e in ipairs(EXPECTED) do
        if e == name then return true end
    end
    return false
end

local function status()
    local lines = { "🎯 Soul Edition 상태" }
    local loaded = 0
    for _, name in ipairs(EXPECTED) do
        local entry = registry[name]
        if entry then
            loaded = loaded + 1
            table.insert(lines, string.format("✅ %s v%s", name, entry.version))
        else
            table.insert(lines, string.format("❌ %s (미로드)", name))
        end
    end
    local extras = {}
    for name, entry in pairs(registry) do
        if not is_expected(name) then
            table.insert(extras, string.format("➕ %s v%s", name, entry.version))
        end
    end
    if #extras > 0 then
        table.insert(lines, "")
        for _, l in ipairs(extras) do table.insert(lines, l) end
    end
    table.insert(lines, "")
    table.insert(lines, string.format("(%d/%d 핵심 + %d 추가)",
        loaded, #EXPECTED, #extras))
    mp.osd_message(table.concat(lines, "\n"), 8)
end

local function cheatsheet()
    local lines = { "🗝 Soul Edition 단축키" }
    for _, name in ipairs(EXPECTED) do
        local entry = registry[name]
        if entry and entry.keys and #entry.keys > 0 then
            table.insert(lines, "")
            table.insert(lines, "[" .. name .. "]")
            for _, kv in ipairs(entry.keys) do
                table.insert(lines, "  " .. kv)
            end
        end
    end
    for name, entry in pairs(registry) do
        if not is_expected(name) and entry.keys and #entry.keys > 0 then
            table.insert(lines, "")
            table.insert(lines, "[" .. name .. " (추가)]")
            for _, kv in ipairs(entry.keys) do
                table.insert(lines, "  " .. kv)
            end
        end
    end
    mp.osd_message(table.concat(lines, "\n"), 15)
end

mp.register_script_message("soul-status", status)
mp.register_script_message("soul-cheatsheet", cheatsheet)

mp.add_timeout(1.0, function()
    local loaded = 0
    for _, name in ipairs(EXPECTED) do
        if registry[name] then loaded = loaded + 1 end
    end
    mp.msg.info(string.format("soul: %d/%d core scripts loaded (F10:status F12:keys)",
        loaded, #EXPECTED))
end)

mp.msg.info("_soul_manager.lua v1.0.1 loaded")
