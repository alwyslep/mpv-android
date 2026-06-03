-- ============================================================
-- jav_osd.lua — JAV 코드/메타 OSD
-- ------------------------------------------------------------
-- keys:
--   i              1회 표시 (5s)
--   I              영구 표시 토글 (1.8s 주기 갱신)
--   Ctrl+C         코드 클립보드 복사 (termux 있을 때만 실제 복사)
--   Ctrl+Shift+C   메타 한 줄 클립보드 복사
--
-- 메타 소스: mpv 'metadata' property (mp4 iTunes-style atom 노출)
-- © 는 UTF-8 0xC2 0xA9
--
-- v1.0.1: buildOsd가 제목에서 코드 토큰을 모두 제거
--         (©nam에 코드가 1~2회 중복 박힌 경우 대응)
-- ============================================================

local lastCode, lastMeta = nil, nil
local persistent = false
local persistentTimer = nil

local CPR = string.char(0xC2, 0xA9)  -- ©

local function parseCode(path)
    if not path or path == "" then return nil end
    local name = path:match("([^/\\]+)$") or ""
    name = name:gsub("%.[^%.]+$", "")
    return name:upper():match("(%u%u+%-%d%d+)")
end

local function pickMeta(md, keys)
    if not md then return nil end
    for _, k in ipairs(keys) do
        local kl = k:lower()
        for key, val in pairs(md) do
            if type(key) == "string" and key:lower() == kl
               and val ~= nil and tostring(val) ~= "" then
                return val
            end
        end
    end
    return nil
end

local function readMeta()
    local md = mp.get_property_native("metadata") or {}
    return {
        title    = pickMeta(md, {"title",        CPR .. "nam", "nam"}),
        artist   = pickMeta(md, {"artist",       CPR .. "ART", "ART"}),
        album    = pickMeta(md, {"album",        CPR .. "alb", "alb"}),
        albumArt = pickMeta(md, {"album_artist", "aART"}),
        date     = pickMeta(md, {"date",         CPR .. "day", "day"}),
        genre    = pickMeta(md, {"genre",        CPR .. "gen", "gen"}),
    }
end

local function buildOsd(code, meta)
    local lines = { "[" .. (code or "UNKNOWN") .. "]" }

    -- 제목에서 코드 토큰을 모두 제거 (©nam에 code가 1~2회 박힌 경우 대응)
    local title = meta.title
    if title and code and code ~= "" then
        local cu = code:upper()
        local changed = true
        while changed do
            changed = false
            local s, e = title:upper():find(cu, 1, true)
            if s then
                title = title:sub(1, s - 1) .. title:sub(e + 1)
                title = title:gsub("%s%s+", " "):gsub("^%s+", ""):gsub("%s+$", "")
                changed = true
            end
        end
    end

    if title and title ~= "" then table.insert(lines, "제목: "     .. tostring(title))          end
    if meta.artist           then table.insert(lines, "배우: "     .. tostring(meta.artist))    end
    if meta.albumArt         then table.insert(lines, "스튜디오: " .. tostring(meta.albumArt))  end
    if meta.album            then table.insert(lines, "시리즈: "   .. tostring(meta.album))     end
    if meta.date             then table.insert(lines, "출시: "     .. tostring(meta.date))      end
    if meta.genre            then table.insert(lines, "장르: "     .. tostring(meta.genre))     end
    return table.concat(lines, "\n")
end

local function refreshCache()
    lastCode = parseCode(mp.get_property("path"))
    lastMeta = readMeta()
end

local function show(duration)
    refreshCache()
    mp.osd_message(buildOsd(lastCode, lastMeta), duration)
end

local function showOnce() show(5) end

local function togglePersistent()
    persistent = not persistent
    if persistent then
        mp.osd_message("JAV OSD 영구표시 ON", 1.5)
        if persistentTimer then persistentTimer:kill() end
        persistentTimer = mp.add_periodic_timer(1.8, function() show(2) end)
        show(2)
    else
        if persistentTimer then
            persistentTimer:kill()
            persistentTimer = nil
        end
        mp.osd_message("JAV OSD 영구표시 OFF", 1.5)
    end
end

local function trySetClipboard(text)
    local res = mp.command_native({
        name = "subprocess",
        args = {"termux-clipboard-set", text},
        playback_only = false,
        capture_stdout = true,
        capture_stderr = true,
    })
    return res and res.status == 0
end

local function copyCode()
    if not lastCode then refreshCache() end
    if lastCode then
        local ok = trySetClipboard(lastCode)
        mp.osd_message("📋 " .. lastCode .. (ok and "" or " (termux 없음 — OSD만)"), 3)
    else
        mp.osd_message("코드 추출 실패", 2)
    end
end

local function copyMeta()
    if not lastMeta then refreshCache() end
    local parts = {}
    if lastCode then table.insert(parts, lastCode) end
    for _, k in ipairs({"title", "artist", "albumArt", "album", "date"}) do
        if lastMeta[k] then table.insert(parts, tostring(lastMeta[k])) end
    end
    local text = table.concat(parts, " | ")
    local ok = trySetClipboard(text)
    mp.osd_message("📋 " .. text .. (ok and "" or "\n(termux 없음 — OSD만)"), 5)
end

mp.register_event("file-loaded", function()
    refreshCache()
    if persistent then show(2) else show(3) end
end)

mp.register_script_message("jav-osd-show", showOnce)
mp.register_script_message("jav-osd-toggle-persistent", togglePersistent)
mp.register_script_message("jav-copy-code", copyCode)
mp.register_script_message("jav-copy-meta", copyMeta)

-- soul_manager 등록
mp.commandv("script-message", "soul-register", "jav_osd", "1.0.1",
    require('mp.utils').format_json({
        "i: 코드/메타 OSD 1회",
        "I: 코드/메타 OSD 영구 토글",
        "Ctrl+C: 코드 클립보드 (termux 필요)",
        "Ctrl+Shift+C: 메타 한 줄 클립보드",
    }))

mp.msg.info("jav_osd.lua v1.0.1 loaded")