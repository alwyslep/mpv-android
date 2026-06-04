-- ============================================================
-- sub_style_toggle.lua — 자막 스타일 프리셋 사이클
-- ------------------------------------------------------------
-- key:  F1   다음 프리셋
-- ============================================================

local presets = {
    {name="기본", size=42, color="#FFFFFFFF", border=2.5, borderColor="#FF000000", shadow=1,   bg="#00000000", marginY=80},
    {name="큼",   size=58, color="#FFFFFFFF", border=3.5, borderColor="#FF000000", shadow=1.5, bg="#00000000", marginY=110},
    {name="박스", size=44, color="#FFFFFFFF", border=0,   borderColor="#FF000000", shadow=0,   bg="#B0000000", marginY=80},
    {name="노랑", size=46, color="#FFFFFF00", border=3,   borderColor="#FF000000", shadow=1.5, bg="#00000000", marginY=90},
}

local idx = 1

local function apply(p)
    mp.set_property_number("sub-font-size", p.size)
    mp.set_property("sub-color", p.color)
    mp.set_property_number("sub-border-size", p.border)
    mp.set_property("sub-border-color", p.borderColor)
    mp.set_property_number("sub-shadow-offset", p.shadow)
    mp.set_property("sub-back-color", p.bg)
    mp.set_property_number("sub-margin-y", p.marginY)
    mp.osd_message(string.format("자막: %s (%d/%d)", p.name, idx, #presets), 1.5)
end

local function cycle()
    idx = idx + 1
    if idx > #presets then idx = 1 end
    apply(presets[idx])
end

-- 32-B: FeaturesActivity on/off (user-data) — off 면 키 무시
local function feat_off() return mp.get_property("user-data/aurora/feat/sub_style_toggle", "true") == "false" end
mp.register_script_message("sub-style-cycle", function() if not feat_off() then cycle() end end)

mp.msg.info("sub_style_toggle.lua loaded — " .. #presets .. " presets")

mp.commandv("script-message", "soul-register", "sub_style_toggle", "1.0.0",
    require('mp.utils').format_json({
        "F1: 프리셋 사이클 (기본/큼/박스/노랑)",
    }))