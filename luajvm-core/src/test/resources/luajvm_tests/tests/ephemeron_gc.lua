-- C：gc.lua : ephemerons
-- 弱键表不能仅因值仍引用键而让键保活。
local t = setmetatable({}, {__mode = "k"})
local key = {}
t[key] = {key}
key = nil

collectgarbage("collect")
assert(next(t) == nil, "ephemeron 键因值反向引用而错误保活")
