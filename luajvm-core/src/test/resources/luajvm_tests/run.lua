-- luajvm_tests/run.lua —— Java 绑定测试聚合入口（由 LuajvmTestRunner 执行）
-- 逐个执行 tests/ 下的测试文件（xpcall 隔离，失败不中断后续），统计汇总；
-- 任何失败最终以 error 结束，runner 依据返回值判定整体 PASS/FAIL。
--
-- 注意：runner 用 org.luaj.bind.Platform.standardGlobals() 装配环境（含 luajava）；
-- 测试文件 require("_fixtures.*") 依赖 package.path 含 tests/ 目录。

package.path = "./tests/?.lua;" .. package.path

local tests = {
  "platform.lua",
  "reflection.lua",
  "coercion.lua",
  "collection.lua",
  "interop.lua",
  "api.lua",
  "stack_realloc.lua",
  "require_chain.lua",
  "weak_key_gc.lua",
  "weak_value_gc.lua",
  "ephemeron_gc.lua",
  "ephemeron_nested_gc.lua",
  "finalizer_alloc_gc.lua",
  "format_align.lua",
  "lvm_align.lua",
  "pattern_align.lua",
  "gc_align.lua",
  "table_align.lua",
  "compiler_align.lua",
  "do_align.lua",
  "traceback_align.lua",
  -- audit_align：把 core 对齐审计发现的逐条 BUG 记录钉成可执行门禁
  --（39 条 BUG + 若干边界项）。C 与 Java 双端必须同时 0 失败：
  --   期望值全部以 lua55-debug 实测为准，故它同时是"C 侧回归探测器"。
  "audit_align.lua",
  -- flatloop_equiv：FlatLoop 全通道的行为等价门禁。现有门禁对 FlatLoop 启停
  --   零判别力（flatShapeTests 在 ON/OFF 两态输出逐字相同），本文件补上那个缺口：
  --   21 条断言覆盖它宣称支持的全部通道，期望值由 C 实测取得 => 双端须 0 失败。
  "flatloop_equiv.lua",
  -- error_align：全标准库错误消息与 C 对齐门禁（80 项）。期望值取自 lua55-debug 实测，
  --   归一化只剥 src:line: 前缀；修复面 = 裸 check 系列换装 + format 手写包装 + 漏检参数。
  "error_align.lua",
  -- ifor_equiv：数值 for 扁平化（FlatIFor）行为等价门禁。正/负步长、零迭代、
  --   大步长无符号边界、除零 bail 续跑的错误消息逐字比对。期望值由 C 实测取得；
  --   -Dluajvm.ifor=false 与默认态各跑一次都必须 0 失败。
  "ifor_equiv.lua",
}

local passed, failed = 0, 0
for _, t in ipairs(tests) do
  io.write("== RUNNING: " .. t .. "\n")
  local ok, err = xpcall(function() return dofile("tests/" .. t) end, debug.traceback)
  if ok then
    passed = passed + 1
    io.write(">>> " .. t .. ": PASS\n")
  else
    failed = failed + 1
    io.write(">>> " .. t .. ": FAIL\n    " .. tostring(err) .. "\n")
  end
end

print(("\nFINAL SUMMARY: %d passed, %d failed"):format(passed, failed))
if failed > 0 then
  error("luajvm_tests: " .. failed .. " test(s) failed")
end
print("luajvm_tests all OK")
