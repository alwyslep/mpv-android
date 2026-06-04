-- aurora_bridge.lua — mpv-aurora 자체(네이티브) 기능을 input.conf 단축키로.
-- is.xyz.mpv 는 client message 인자를 Kotlin 으로 넘기지 않으므로, script-message 를
-- user-data/aurora/cmd 프로퍼티에 "<명령>#<seq>" 로 기록 → 앱이 observeProperty 로 받아 디스패치.
-- seq 를 붙여 같은 명령을 연속으로 눌러도 값이 바뀌어 콜백이 보장된다.

local seq = 0
local function send(cmd)
    seq = seq + 1
    mp.set_property("user-data/aurora/cmd", cmd .. "#" .. seq)
end

for _, c in ipairs({ "thumb", "lock", "pip", "menu", "exit" }) do
    mp.register_script_message("aurora-" .. c, function() send(c) end)
end
