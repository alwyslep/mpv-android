-- ============================================================
-- bookmarks.lua — 파일별 책갈피 (단일 JSON DB)
-- ------------------------------------------------------------
-- keys:
--   b       추가
--   B       리스트 OSD
--   Ctrl+b  가까운 책갈피 1개 삭제
--   Alt+b   이 파일 책갈피 전체 삭제
--   '       이전 책갈피로 이동
--   ;       다음 책갈피로 이동
--
-- 저장: /storage/emulated/0/mpv/bookmarks.json
-- 키: 파일명만 (NAS↔로컬 경로 바뀌어도 매칭)
-- ============================================================

local utils = require 'mp.utils'

local DB_PATH   = "/storage/emulated/0/mpv/bookmarks.json"
local SEEK_DEAD = 0.5

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
        mp.msg.error("bookmarks save failed: open")
        mp.osd_message("⚠ 책갈피 저장 실패 (mpv 폴더 권한 확인)", 3)
        return
    end
    f:write(s)
    f:close()
end

local function pad2(n)
    if n < 10 then return "0" .. n else return tostring(n) end
end

local function fmtTime(t)
    t = math.floor(t)
    local h = math.floor(t / 3600)
    local m = math.floor((t % 3600) / 60)
    local s = t % 60
    if h > 0 then return string.format("%d:%s:%s", h, pad2(m), pad2(s)) end
    return string.format("%s:%s", pad2(m), pad2(s))
end

local function currentKey()
    local p = mp.get_property("path")
    if not p or p == "" then return nil end
    return p:match("([^/\\]+)$")
end

local function curT()
    return mp.get_property_number("time-pos") or 0
end

local function sortByT(arr)
    table.sort(arr, function(a, b) return a.t < b.t end)
end

local function add()
    local k = currentKey()
    if not k then return end
    local t = curT()
    if not db[k] then db[k] = {} end
    for _, b in ipairs(db[k]) do
        if math.abs(b.t - t) < SEEK_DEAD then
            mp.osd_message("이미 책갈피 있음 (" .. fmtTime(t) .. ")", 1.5)
            return
        end
    end
    table.insert(db[k], {t = t, ts = os.time() * 1000})
    sortByT(db[k])
    save_db()
    mp.osd_message(string.format("⭐ %s 책갈피 (%d개)", fmtTime(t), #db[k]), 2)
end

local function list()
    local k = currentKey()
    if not k or not db[k] or #db[k] == 0 then
        mp.osd_message("책갈피 없음", 2); return
    end
    local arr = db[k]
    local lines = { "📑 책갈피 (" .. #arr .. ")" }
    local cur = curT()
    for i, b in ipairs(arr) do
        local marker = (math.abs(b.t - cur) < SEEK_DEAD) and " ◀ 현재" or ""
        table.insert(lines, string.format("%d. %s%s", i, fmtTime(b.t), marker))
    end
    mp.osd_message(table.concat(lines, "\n"), 5)
end

local function removeLast()
    local k = currentKey()
    if not k or not db[k] or #db[k] == 0 then
        mp.osd_message("삭제할 책갈피 없음", 2); return
    end
    local t = curT()
    local arr = db[k]
    local idx = -1
    for i = #arr, 1, -1 do
        if arr[i].t <= t + SEEK_DEAD then idx = i; break end
    end
    if idx < 0 then idx = #arr end
    local removed = arr[idx]
    table.remove(arr, idx)
    if #arr == 0 then db[k] = nil end
    save_db()
    local remaining = db[k] and #db[k] or 0
    mp.osd_message(string.format("🗑 %s 삭제 (%d개 남음)", fmtTime(removed.t), remaining), 2)
end

local function clearAll()
    local k = currentKey()
    if not k or not db[k] then
        mp.osd_message("책갈피 없음", 2); return
    end
    db[k] = nil
    save_db()
    mp.osd_message("이 파일 책갈피 전체 삭제", 2)
end

local function nextMark()
    local k = currentKey()
    if not k or not db[k] or #db[k] == 0 then
        mp.osd_message("책갈피 없음", 1.5); return
    end
    local t = curT()
    local arr = db[k]
    for i, b in ipairs(arr) do
        if b.t > t + SEEK_DEAD then
            mp.commandv("seek", tostring(b.t), "absolute", "exact")
            mp.osd_message(string.format("→ %s (%d/%d)", fmtTime(b.t), i, #arr), 1.5)
            return
        end
    end
    mp.osd_message("마지막 책갈피", 1.5)
end

local function prevMark()
    local k = currentKey()
    if not k or not db[k] or #db[k] == 0 then
        mp.osd_message("책갈피 없음", 1.5); return
    end
    local t = curT()
    local arr = db[k]
    for i = #arr, 1, -1 do
        if arr[i].t < t - SEEK_DEAD then
            mp.commandv("seek", tostring(arr[i].t), "absolute", "exact")
            mp.osd_message(string.format("← %s (%d/%d)", fmtTime(arr[i].t), i, #arr), 1.5)
            return
        end
    end
    mp.osd_message("첫 책갈피", 1.5)
end

load_db()

-- 32-B: FeaturesActivity on/off (user-data) — off 면 키 무시
local function feat_off() return mp.get_property("user-data/aurora/feat/bookmarks", "true") == "false" end
local function gated(fn) return function(...) if not feat_off() then fn(...) end end end
mp.register_script_message("bookmark-add", gated(add))
mp.register_script_message("bookmark-list", gated(list))
mp.register_script_message("bookmark-remove-last", gated(removeLast))
mp.register_script_message("bookmark-clear", gated(clearAll))
mp.register_script_message("bookmark-next", gated(nextMark))
mp.register_script_message("bookmark-prev", gated(prevMark))

local cnt = 0
for _ in pairs(db) do cnt = cnt + 1 end
mp.msg.info("bookmarks.lua loaded — " .. cnt .. " files")

mp.commandv("script-message", "soul-register", "bookmarks", "1.0.0",
    require('mp.utils').format_json({
        "b: 추가",
        "B: 리스트 OSD",
        "Ctrl+b: 가까운 책갈피 1개 삭제",
        "Alt+b: 이 파일 전체 삭제",
        "': 이전 책갈피",
        ";: 다음 책갈피",
    }))