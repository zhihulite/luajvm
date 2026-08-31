local ok1, e1 = pcall(function()
  local Math = luajava.bindClass("java.lang.Math")
  return Math.abs(-5) == 5
end)
print("1 abs: " .. tostring(ok1) .. " " .. tostring(e1))
local ok2, e2 = pcall(function()
  local Integer = luajava.bindClass("java.lang.Integer")
  return Integer.MAX_VALUE > 0
end)
print("2 MAX_VALUE: " .. tostring(ok2) .. " " .. tostring(e2))
local ok3, e3 = pcall(function()
  local sb = luajava.newInstance("java.lang.StringBuilder")
  sb:append("a")
  sb.append("b")
  return tostring(sb) == "ab"
end)
print("3 sb: " .. tostring(ok3) .. " " .. tostring(e3))
print("DONE02")
