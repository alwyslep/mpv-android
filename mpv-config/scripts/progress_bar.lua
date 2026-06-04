-- ============================================================
-- progress_bar.lua — 상단 얇은 재생 진행바 + 남은시간(95%+)
-- ------------------------------------------------------------
-- 자동 동작 (키 없음)
--   - 화면 상단 끝에 얇은 진행바 (좌→우, 화면폭의 95%)
--   - 진행바 색: 밝은 노란색
--   - 재생률 95% 이상이면 우측 5% 구역에 남은시간 "MM:SS"
--     글자색: 밝은 오렌지
--
-- 튜닝: BAR_H(두께), BAR_FRAC(길이비율), 색상 상수 조절
-- ============================================================

local overlay = mp.create_osd_overlay("ass-events")

-- ---- 설정 ----
local BAR_H          = 4         -- 바 두께 (px). 더 두껍게 원하면 6~8
local BAR_FRAC       = 0.95      -- 바 길이 = 화면폭 * 0.95
local SHOW_REMAIN_AT = 0.95      -- 이 비율 이상이면 남은시간 표시
local COL_FILL       = "&H00FFFF&"   -- 진행바: 밝은 노란색 (ASS는 BGR)
local COL_TRACK      = "&H000000&"   -- 배경 트랙: 검정
local TRACK_ALPHA    = "&HA0&"       -- 트랙 반투명도 (00=불투명 FF=투명)
local COL_REMAIN     = "&H00A5FF&"   -- 남은시간 글자: 밝은 오렌지 (BGR)

-- 시킹 시 시간 텍스트 (상단 바 바로 아래, 잠깐 표시 후 사라짐)
local SEEK_TEXT_DUR  = 0.8           -- 마지막 시킹 후 유지 시간 (초)
local COL_SEEK       = "&HFFFFFF&"   -- 시킹 시간 글자: 흰색 (BGR)
local seek_until     = 0             -- mp.get_time() 기준, 이 시각까지 표시

-- 초 → "H:MM:SS" 또는 "MM:SS"
local function fmt_time(t)
    if not t or t < 0 then t = 0 end
    local h = math.floor(t / 3600)
    local m = math.floor((t % 3600) / 60)
    local s = math.floor(t % 60)
    if h > 0 then return string.format("%d:%02d:%02d", h, m, s) end
    return string.format("%02d:%02d", m, s)
end

-- 32-B: 네이티브(FeaturesActivity)가 user-data/aurora/feat/progress_bar 로 on/off 전달 → 런타임 토글.
--   observe 콜백 의존 대신 draw(0.25s 주기)에서 매번 직접 읽어 확실히 반영.
local function feat_off()
    return mp.get_property("user-data/aurora/feat/progress_bar", "true") == "false"
end

local function draw()
    if feat_off() then
        if overlay.data ~= "" then overlay.data = ""; overlay:update() end
        return
    end
    local pos = mp.get_property_number("time-pos")
    local dur = mp.get_property_number("duration")
    local osd_w, osd_h = mp.get_osd_size()

    if not pos or not dur or dur <= 0 or not osd_w or osd_w < 1 then
        if overlay.data ~= "" then
            overlay.data = ""
            overlay:update()
        end
        return
    end

    overlay.res_x = osd_w
    overlay.res_y = osd_h

    local frac = pos / dur
    if frac < 0 then frac = 0 elseif frac > 1 then frac = 1 end

    local track_w = osd_w * BAR_FRAC
    local fill_w  = track_w * frac

    local parts = {}

    -- 배경 트랙
    parts[#parts+1] = string.format(
        "{\\an7\\pos(0,0)\\bord0\\shad0\\1c%s\\1a%s\\p1}" ..
        "m 0 0 l %.1f 0 l %.1f %.1f l 0 %.1f{\\p0}",
        COL_TRACK, TRACK_ALPHA, track_w, track_w, BAR_H, BAR_H)

    -- 진행 바 (노란색)
    if fill_w > 0 then
        parts[#parts+1] = string.format(
            "{\\an7\\pos(0,0)\\bord0\\shad0\\1c%s\\1a&H00&\\p1}" ..
            "m 0 0 l %.1f 0 l %.1f %.1f l 0 %.1f{\\p0}",
            COL_FILL, fill_w, fill_w, BAR_H, BAR_H)
    end

    -- 남은시간 (95% 이상일 때만, 우측 5% 구역)
    if frac >= SHOW_REMAIN_AT then
        local remain = dur - pos
        if remain < 0 then remain = 0 end
        local m = math.floor(remain / 60)
        local s = math.floor(remain % 60)
        local txt = string.format("%02d:%02d", m, s)

        local font_size = osd_h * 0.025
        if font_size < 14 then font_size = 14 end

        -- 우측 5% 구역의 중앙
        local text_x = osd_w * (BAR_FRAC + (1 - BAR_FRAC) / 2)
        parts[#parts+1] = string.format(
            "{\\an8\\pos(%.1f,0)\\bord1.5\\shad0\\1c%s\\3c&H000000&\\fs%.1f\\b1}%s",
            text_x, COL_REMAIN, font_size, txt)
    end

    -- 시킹 직후 현재시간/총시간 잠깐 표시 (상단 바 바로 아래 중앙)
    if mp.get_time() < seek_until then
        local font_size = osd_h * 0.040
        if font_size < 22 then font_size = 22 end
        local txt = fmt_time(pos) .. " / " .. fmt_time(dur)
        parts[#parts+1] = string.format(
            "{\\an8\\pos(%.1f,%.1f)\\bord2\\shad0\\1c%s\\3c&H000000&\\fs%.1f\\b1}%s",
            osd_w / 2, BAR_H + 6, COL_SEEK, font_size, txt)
    end

    overlay.data = table.concat(parts, "\n")
    overlay:update()
end

-- [DEBUG v77] user-data 전달 확인용 — 화면 좌상단에 feat 값 항상 표시. 원인 확정 후 제거.
local dbg_ov = mp.create_osd_overlay("ass-events")
mp.add_periodic_timer(0.5, function()
    local v = mp.get_property("user-data/aurora/feat/progress_bar", "NIL")
    dbg_ov.data = string.format("{\\an7\\pos(12,90)\\fs30\\bord2\\1c&H00FF00&}PB feat=%s", tostring(v))
    dbg_ov:update()
end)

-- 0.25초 주기 갱신 (시킹 즉시성 + 가벼움 균형)
local timer = mp.add_periodic_timer(0.25, draw)

mp.register_event("file-loaded", draw)
mp.register_event("seek", function()
    seek_until = mp.get_time() + SEEK_TEXT_DUR
    draw()
end)
mp.register_event("shutdown", function()
    if timer then timer:kill() end
    overlay:remove()
end)

-- soul_manager 등록
mp.commandv("script-message", "soul-register", "progress_bar", "1.1.0",
    require('mp.utils').format_json({
        "(자동) 상단 얇은 진행바 + 95%+ 남은시간 + 시킹 시 시간표시",
    }))

mp.msg.info("progress_bar.lua loaded")