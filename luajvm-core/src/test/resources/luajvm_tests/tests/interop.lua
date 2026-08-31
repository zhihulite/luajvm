-- luajvm_tests/tests/interop.lua —— Java 互操作行为回归
-- 覆盖：bindClass/newInstance/实例与静态方法调用/字段访问/重载打分/
--       返回值与参数转换（int/String/boolean）/JavaObject 内容 toString
-- 失败即 error，由 run.lua 汇总。
-- 注意：java.lang.Math 的字段（PI 等 final 静态）走 cachedStaticFinals 缓存路径，
--       非 final 静态字段/实例字段走 Field spreader 路径——都用 java.util.ArrayList
--       的 public 字段 size（实例非 final）验证。

local function check(cond, msg)
  if not cond then error("interop.lua: " .. msg, 2) end
end

-- 1. bindClass + 静态方法调用 + 静态 final 字段
local Math = luajava.bindClass("java.lang.Math")
check(Math ~= nil, "bindClass(Math) nil")
check(Math.max(3, 7) == 7, "Math.max(int,int) 静态方法")
check(Math.min(3.5, 2.5) == 2.5, "Math.min(double,double) 浮点参数")
check(Math.abs(-5) == 5, "Math.abs(int) 负数")

-- 2. newInstance + 实例方法（重载打分：int vs String 交替 → Overload IC 抖动路径）
local sb = luajava.newInstance("java.lang.StringBuilder")
check(sb ~= nil, "newInstance(StringBuilder) nil")
sb:append(123)          -- append(int)
sb:append("x")          -- append(String)
sb:append(true)         -- append(boolean)
check(sb:toString() == "123xtrue", "StringBuilder 重载交替 append")

-- 3. 返回值转换：int→LuaInteger、String→LuaString、boolean→LuaBoolean
local b = luajava.newInstance("java.lang.Boolean", true)
check(b:booleanValue() == true, "booleanValue 返回 boolean")

-- 4. 字段访问：InteropTestBean 的 public 非 final 字段 → Field spreader 路径
local InteropTestBean = luajava.bindClass("org.luajvm.test.InteropTestBean")
local bean = luajava.newInstance("org.luajvm.test.InteropTestBean")
check(bean.count == 0, "bean.count 初始 0（实例字段读）")
bean.count = 5
check(bean.count == 5, "bean.count 写后 5（实例字段写）")
bean.name = "abc"
check(bean.name == "abc", "bean.name String 字段往返")
InteropTestBean.total = 9
check(InteropTestBean.total == 9, "static total 读写（静态字段 spreader）")
check(InteropTestBean.MAGIC == 42, "MAGIC final 静态（cachedStaticFinals）")
check(bean:add(3, 4) == 7, "bean:add(int,int) 实例方法")
check(InteropTestBean:staticAdd(2, 3) == 5, "staticAdd 静态方法")

-- 5. 集合互操作：add/get/remove + int 装箱往返
local al = luajava.newInstance("java.util.ArrayList")
al:add(1)
al:add(2)
al:add(42)
check(al:get(2) == 42, "ArrayList.get 返回 Integer→LuaInteger")
check(al:size() == 3, "ArrayList.size() 方法")
al:remove(1)
check(al:size() == 2, "ArrayList.remove 后 size")

-- 6. 字符串参数 + 字符串拼接往返
local s = luajava.newInstance("java.lang.String", "hello")
check(s:length() == 5, "String.length()")
check(s:toUpperCase() == "HELLO", "String.toUpperCase()")

-- 7. 构造器重载：String(char[]) / String(String) / String(byte[], charset)
local ch = luajava.newInstance("java.lang.String", luajava.newArray(luajava.bindClass("java.lang.Character").TYPE, 0))
check(ch:length() == 0, "String(char[0]) 构造器")

-- 8. Lua→Java 数组参数：Object[] 变长参数方法
local formatter = luajava.bindClass("java.lang.String")
local fmt = luajava.newInstance("java.lang.String", "%d-%s")
-- String.format 是静态 varargs 方法（Method.invoke 回退路径）
local formatted = formatter:format("%d-%s", 7, "k")
check(formatted == "7-k", "String.format varargs 静态方法")

-- 9. JavaObject toString（内容文本，Java 侧显示）
check(tostring(s) == "hello", "JavaObject tostring 返回内容")

-- 10. 链式调用（方法返回对象继续调用）
local s2 = luajava.newInstance("java.lang.StringBuilder"):append("a"):append("b"):toString()
check(s2 == "ab", "StringBuilder 链式调用")

print("interop.lua PASS")
