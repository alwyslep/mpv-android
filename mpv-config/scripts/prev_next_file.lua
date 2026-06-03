-- ############################################################
-- ## ⛔ DEPRECATED / 비활성 (2026-06-01) — mpv.conf 에서 script= 주석됨.
-- ## 사유(중복 아닌 *충돌*): 포크 앱(p1-launcher)이 자동 다음 재생을 라이브러리
-- ##   레이어에 네이티브 구현(`autoplay_next` pref, `Playback.shouldAdvance`,
-- ##   전 화면 — 홈/폴더/트리/검색/SAF/분류). 네이티브 플로우는 영상 끝 → 플레이어가
-- ##   라이브러리로 *복귀(for-result)* 하며 진행률·시청상태·트랙 선택을 기록한다.
-- ##   그런데 본 Lua 는 EOF 를 가로채 인-플레이어로 다음 파일을 loadfile → 라이브러리로
-- ##   안 돌아가 그 기록이 통째 우회됨 = 2026-06-01 네이티브 작업 무력화. 그래서 비활성.
-- ##   (인-플레이어 PgDn/PgUp 수동 점프도 같은 우회 문제라 함께 off.)
-- ## 되살리기: mpv.conf 의 `#script=.../prev_next_file.lua` 주석 해제. 본문은 보존.
-- ## SSOT: docs/player_meta_translation.md §1.6.
-- ############################################################
-- ============================================================
-- prev_next_file.lua — 같은 폴더 다음/이전 영상 재생 + 자동 진행
-- ------------------------------------------------------------
-- keys:
--   PgDn    다음 영상 (같은 폴더, 이름 정렬)
--   PgUp    이전 영상
--
-- 자동 진행:
--   영상이 정상적으로 끝나면(eof) 같은 폴더 다음 영상 자동 재생.
--   마지막 영상이면 멈춤. 수동 종료/에러는 자동 진행 안 함.
--
-- 동작:
--   현재 영상의 폴더를 스캔 → 정렬 → 현재 위치 찾음 → ±1 → loadfile
--   autoload.lua 없이도 동작.
--   loadfile은 file-loaded 이벤트를 발생시켜 jav_osd / favorite /
--   playstats 등 다른 lua가 모두 정상 동작.
-- ============================================================

local utils = require 'mp.utils'

-- 자동 진행 on/off (false로 두면 키 점프만)
local AUTO_ADVANCE = true

-- 비디오 확장자
local VIDEO_EXTS = {
    mp4 = true, m4v = true, mov = true,
    mkv = true, avi = true, webm = true,
    ts  = true, m2ts = true, wmv = true,
}

local function list_videos()
    local path = mp.get_property("path")
    if not path or path == "" then return nil end

    local dir, current = utils.split_path(path)
    local files = utils.readdir(dir, "files")
    if not files then return nil end

    local videos = {}
    for _, f in ipairs(files) do
        local ext = f:match("%.([^%.]+)$")
        if ext and VIDEO_EXTS[ext:lower()] then
            table.insert(videos, f)
        end
    end
    table.sort(videos)

    return dir, videos, current
end

-- silent=true 면 OSD/경계 메시지 없이 조용히 (자동 진행 시 사용)
local function jump(direction, silent)
    local dir, videos, current = list_videos()
    if not videos or #videos == 0 then
        if not silent then mp.osd_message("같은 폴더에 영상 없음", 1.5) end
        return false
    end

    -- 현재 위치 찾기
    local idx = nil
    for i, v in ipairs(videos) do
        if v == current then idx = i; break end
    end
    if not idx then
        -- 현재 파일이 같은 폴더에 없는 경우 (URI 등) — 첫/마지막으로
        idx = (direction > 0) and 0 or (#videos + 1)
    end

    local target_idx = idx + direction
    if target_idx < 1 then
        if not silent then mp.osd_message("첫 영상 (이전 없음)", 1.5) end
        return false
    end
    if target_idx > #videos then
        if not silent then mp.osd_message("마지막 영상 (다음 없음)", 1.5) end
        return false
    end

    local target = utils.join_path(dir, videos[target_idx])
    mp.commandv("loadfile", target, "replace")
    mp.osd_message(string.format("(%d/%d) %s",
        target_idx, #videos, videos[target_idx]), 2)
    return true
end

-- next-video = jump(+1) = 다음 영상
-- prev-video = jump(-1) = 이전 영상
mp.register_script_message("next-video", function() jump(1) end)
mp.register_script_message("prev-video", function() jump(-1) end)

-- 자동 진행: 정상 종료(eof)일 때만 다음 영상
mp.register_event("end-file", function(ev)
    if not AUTO_ADVANCE then return end
    if ev.reason ~= "eof" then return end  -- 수동 종료/에러/loadfile 교체는 무시
    -- end-file 처리 도중 즉시 loadfile 하면 불안정 → 짧게 지연
    mp.add_timeout(0.1, function()
        jump(1, true)
    end)
end)

-- soul_manager 등록
mp.commandv("script-message", "soul-register", "prev_next_file", "1.1.0",
    utils.format_json({
        "PgDn: 다음 영상 (같은 폴더)",
        "PgUp: 이전 영상 (같은 폴더)",
        "(자동) 영상 끝나면 다음 영상 자동 재생",
    }))

mp.msg.info("prev_next_file.lua v1.1.0 loaded")