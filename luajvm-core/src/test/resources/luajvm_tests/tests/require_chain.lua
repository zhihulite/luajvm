-- luajvm_tests/07_require_chain.lua
-- 深层 require 链 + _G/局部变量接收返回值
-- 链：07 → _fixtures.chain_a → _fixtures.chain_b → _fixtures.chain_c
local mod = require("_fixtures.chain_a")
assert(mod.depth == 3, "chain depth")
-- require 缓存：再次 require 返回同一模块
local again = require("_fixtures.chain_a")
assert(again == mod, "require cache")
-- _G 接收返回值（模块加载进 _G）
_G.loaded_by_test = require("_fixtures.chain_c")
assert(_G.loaded_by_test.depth == 1, "_G receive")
print("07_require_chain OK")
