-- ============================================================
-- precise_speed.lua — 0.05 단위 정밀 배속
-- ------------------------------------------------------------
-- keys:
--   [          0.05 감속
--   ]          0.05 가속
--   BS         1.0 리셋
-- 범위: 0.25 ~ 4.0
-- ============================================================

local STEP, MIN_S, MAX_S = 0.05, 0.25, 4.0

local function clamp(v)
    local r = math.max(MIN_S, math.min(MAX_S, v))
    return math.floor(r * 100 + 0.5) / 100
end

local function show(speed)
    mp.osd_message(string.format("⏩ %.2fx", speed), 1)
end

local function up()
    local s = clamp(mp.get_property_number("speed") + STEP)
    mp.set_property_number("speed", s)
    show(s)
end

local function down()
    local s = clamp(mp.get_property_number("speed") - STEP)
    mp.set_property_number("speed", s)
    show(s)
end

local function reset()
    mp.set_property_number("speed", 1.0)
    show(1.0)
end

-- 32-B: FeaturesActivity on/off (user-data) — off 면 키 무시
local function feat_off() local v = mp.get_property_native("user-data/aurora/feat/precise_speed", true); return v == false or v == "false" or v == "no" or v == "0" end
local function gated(fn) return function(...) if not feat_off() then fn(...) end end end
mp.register_script_message("precise-speed-up", gated(up))
mp.register_script_message("precise-speed-down", gated(down))
mp.register_script_message("precise-speed-reset", gated(reset))

mp.msg.info("precise_speed.lua loaded")

mp.commandv("script-message", "soul-register", "precise_speed", "1.0.0",
    require('mp.utils').format_json({
        "[: 0.05 감속",
        "]: 0.05 가속",
        "BS: 1.0 리셋",
    }))