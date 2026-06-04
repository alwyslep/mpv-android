-- ============================================================
-- screenshot_to_clip.lua — 스크린샷 + 파일명 코드 클립보드
-- ------------------------------------------------------------
-- key: s
-- 클립보드: termux-clipboard-set 호출. termux 없으면 OSD까지만.
-- ============================================================

local function take()
    local path = mp.get_property("path") or ""
    local name = path:match("([^/\\]+)$") or ""
    local code = name:upper():match("(%u%u+%-%d%d+)")

    mp.command("screenshot video")

    if code then
        local res = mp.command_native({
            name = "subprocess",
            args = {"termux-clipboard-set", code},
            playback_only = false,
            capture_stdout = true,
            capture_stderr = true,
        })
        local ok = res and res.status == 0
        mp.osd_message("📸 " .. code .. (ok and " (클립보드)" or ""), 2)
    else
        mp.osd_message("📸 스크린샷", 2)
    end
end

-- 32-B: FeaturesActivity on/off (user-data) — off 면 키 무시
local function feat_off() local v = mp.get_property_native("user-data/aurora/feat/screenshot_to_clip", true); return v == false or v == "false" or v == "no" or v == "0" end
mp.register_script_message("screenshot-clip-take", function() if not feat_off() then take() end end)

mp.msg.info("screenshot_to_clip.lua loaded")

mp.commandv("script-message", "soul-register", "screenshot_to_clip", "1.0.0",
    require('mp.utils').format_json({
        "s: 스크린샷 + 코드 클립보드",
    }))