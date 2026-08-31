-- luajvm_tests/06_stack_realloc.lua
-- VM 栈 realloc：深递归 + 调用后继续使用寄存器 + 元方法 + 变参
-- 深递归触发 checkStack/growStack
local function deep(n)
  if n == 0 then return 0 end
  return 1 + deep(n - 1)
end
assert(deep(150) == 150, "deep recursion")
-- 调用后继续使用寄存器（栈可能已 realloc）
local function f() return 42 end
local a, b = 1, f()
assert(a == 1 and b == 42, "reg after call")
-- 元方法（算术 metamethod 触发 callTMres）
local mt = { __add = function(x, y) return x.v + y.v end }
local x, y = setmetatable({v = 10}, mt), setmetatable({v = 5}, mt)
assert(x + y == 15, "metamethod add")
-- 变参
local function var(...) return select("#", ...) end
assert(var(1, 2, 3) == 3, "vararg")
print("06_stack_realloc OK")
