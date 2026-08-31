-- official_tests/run.lua —— 官方套件聚合入口（由 LuaOfficalTestRunner 执行）
-- 加载并运行 lua-5.5.1-tests/all.lua（官方聚合套件：dofile 全部测试，输出 "final OK !!!"）。
--
-- _port = true：main.lua（stand-alone CLI 交互测试，main.lua:6 `if _port then return end`）
-- 等 Windows 无法执行的测试直接跳过。
--
-- 相对路径基准：runner 已将引擎 cwd 设为 lua-5.5.1-tests（官方套件目录）——
-- 与 C 直接在该目录跑 all.lua 的环境一致（官方 all.lua 内部用 dofile('main.lua')/
-- loadfile('gc.lua') 等相对路径，基于 cwd 解析）。所有测试（含 files.lua 的
-- os.tmpname 临时文件 io.open/dofile）共享同一 cwd，无需 loadfile/dofile 前缀
-- workaround（此前 prefix 会把 files.lua 的 dofile(".luaj*.tmp") 也拼上套件
-- 子目录前缀，导致临时文件找不到——2026-08-05 修复）。
--
-- 注意保留缺省参数语义：luaB_loadfile 用 lua_isnone 区分"未传"与"显式 nil"——
-- 未传 env 时 chunk 环境 = 全局 _G；显式 nil 则 _ENV = nil（对齐 C）。
-- 入口文件：org.luaj.test.LuaOfficalTestRunner

-- 不设 _soft / _nomsg：完整强度运行（栈溢出、深递归、超长数值、big.lua、长链表 GC），
--   且不静默 all.lua 的「未执行」提示。
-- 不设 _U：会把 T 置 nil，连带跳过大量 `if T then` 守卫段（"部分通过"假象）。
-- 保留 _port：守的是「假定 POSIX 目录布局与动态库」的段落。依据是 C 参照实现在本机
--   去掉 _port 后同样失败且失败点完全相同——attrib.lua:154 的 try（由 169 行
--   try('B','B.lua',true,"libs/B.lua") 触发，期望值硬编码正斜杠而 Windows dirsep 是 '\'）；
--   main.lua 更早，第 41 行 os.tmpname() 即返回 Windows 无效路径。
_port = true

-- require 路径：套件目录（cwd）+ libs 辅助库（tracegc.lua 在套件根、attrib.lua 依赖 libs/err.lua）。
package.path = "libs/?.lua;?.lua;?;" .. package.path

-- 以全局环境加载官方聚合套件并执行（all.lua 相对 cwd 解析）
assert(loadfile("all.lua"))()
