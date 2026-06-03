-- ============================================================
-- multiwindow_fix.lua — DeX 멀티윈도/리사이즈 시 OSD 폰트 자동 보정
-- ------------------------------------------------------------
-- 자동 동작 (키 바인딩 없음)
-- 자막은 손대지 않음 (sub_style_toggle.lua와 충돌 방지)
-- ============================================================

local BASE_W, BASE_OSD = 3440, 32
local MIN_OSD, MAX_OSD = 18, 44

local timer = nil
local lastW = -1

local function apply()
    local w = mp.get_property_number("osd-width")
    if not w or w < 320 then return end
    if math.abs(w - lastW) < 40 then return end
    lastW = w

    local ratio = w / BASE_W
    if ratio < 0.4 then ratio = 0.4 elseif ratio > 1.3 then ratio = 1.3 end
    local osd = math.floor(BASE_OSD * ratio + 0.5)
    if osd < MIN_OSD then osd = MIN_OSD end
    if osd > MAX_OSD then osd = MAX_OSD end

    mp.set_property_number("osd-font-size", osd)
    mp.msg.info(string.format("resize: w=%d osd=%d", w, osd))
end

mp.observe_property("osd-width", "number", function()
    if timer then timer:kill() end
    timer = mp.add_timeout(0.2, apply)
end)

mp.msg.info("multiwindow_fix.lua loaded")

mp.commandv("script-message", "soul-register", "multiwindow_fix", "1.0.0",
    require('mp.utils').format_json({
        "(자동) DeX 리사이즈 시 OSD 폰트 보정",
    }))