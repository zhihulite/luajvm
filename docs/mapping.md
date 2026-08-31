# C ↔ Java 映射表

> 本表由 `scripts/gen-mapping.py` 从代码注释自动生成，**不要手改**；口径变更改脚本后重新生成。
> 口径：文件级来自文件头 `// ref:` / `// diff:`；函数级来自代码内 `// <C文件>: <C函数>` 注释；
> Java 方法名取映射注释后的第一条代码行，个别为字段/类型名。
> 热路径写法不对齐 C 的类（HOT-EXEMPT）语义仍与本表对应，见 docs/performance.md。

## 一、文件级映射（翻译自 C 的类）

| C 源文件 | Java 类 | 类级差异（文件头 diff） |
|---|---|---|
| lapi.c | `core.LuaCClosure` | Java没有lua_CFunction函数指针；C函数体通过LuaFunction子类/override承载，upvalue槽位按CClosure保存。 |
| lbaselib.c | `core.NextMark` | — |
| lbaselib.c | `lib.BaseLib` | try/catch 代替 longjmp; InputStream 代替 FILE*; LuaError 异常; pcall 手动恢复 nCcalls/nny/allowhook/ci/top; LuaGC 静态方法; warn 枚举状态机 |
| lcode.c | `compiler.CodeGen` | KCache去重(非线性扫描); expdesc需copyFrom; foldV1/foldV2/foldRes复用; Arrays.copyOf+MIN_JAVA_PARSE_ARRAY; SETARG写回; freereg检查REG_IS_TEMPORARY; TESTSET用NO_REG(255); fillidxk重置ind_ro; luaK_finish裁剪数组 |
| lcorolib.c | `lib.CoroutineLib` | 内部类代替C闭包; LuaThread代替lua_State线程; Globals传递全局状态; auxstatus返回COS_*常量; CoWrapperFn对应luaB_auxwrap |
| ldblib.c | `lib.DebugHook` | db_debug 简化为空实现；CallInfo 链遍历设 trap；DebugFrame 链替代 CallInfo 链；LuaError.savedStack 处理 finally 块提前弹出帧 |
| ldblib.c | `lib.DebugInfo` | Info 类替代 lua_Debug 结构体；ldebug.c 的名字/行号解析已下移到 core.LuaDebug（见其类注释） |
| ldblib.c | `lib.DebugLib` | 核心函数拆分到DebugInfo/DebugHook; upvalueid用UpVal/LuaCClosure槽位对象代替C指针; UpVal.get()/set()保持共享upvalue同步 |
| ldebug.c | `core.LuaDebug` | NameWhat 类替代 C 的 const char **name 输出参数；FindLocal 替代 luaG_findlocal 的 |
| ldebug.c | `core.LuaErrors` | C的luaG_runerror立即调用luaG_addinfo；Java拆runError/runErrorWithInfo两方法，runErrorWithInfo在构造错误时立即添加源码位置 |
| ldo.c | `core.LuaError` | C用longjmp传播错误，CallInfo链在longjmp后仍存活；Java用RuntimeException，finally块会在xpcall的catch前弹出DebugFrame，因此创建错误时快照CallInfo视图(savedStack) |
| ldo.c | `vm.LuaCall` | instanceof替代ttypetag switch \| Varargs桥接precallC \| nCcalls/nny拆分 |
| ldump.c | `vm.LuaChunk` | OutputStream/InputStream 替代 ZIO, ArrayList 替代 luaM_reallocvector, RuntimeException 替代 longjmp |
| lfunc.c | `core.LuaCClosure` | Java没有lua_CFunction函数指针；C函数体通过LuaFunction子类/override承载，upvalue槽位按CClosure保存。 |
| lfunc.c | `core.UpVal` | parentStack+slot 替代 TValue* 指针；UpVal 非 GC 对象（C 是 GCObject 带 marked） -；luaF_closeupval 的 nw2black+barrier(L,uv,slot) 简化为 barrier(value) |
| lgc.c | `core.LuaGC` | gray/grayAgain 用侵入式 GrayList 链表（LuaValue.gclist 作 next 指针）对齐 C 的 g->gray 链表 |
| linit.c | `LuaStandard` | — |
| linit.c | `vm.LuaPlatform` | bareGlobals=lua_newstate, standardGlobals+=luaL_openlibs |
| liolib.c | `lib.IoFile` | RandomAccessFile/InputStream/OutputStream 替代 FILE*；手动写缓冲替代 setvbuf；正则解析数字替代 luaO_str2num；静态列表跟踪句柄 |
| liolib.c | `lib.IoLib` | RandomAccessFile/InputStream/OutputStream 代替 FILE*；LuaUserdata 代替 luaL_Stream；ProcessBuilder 代替 popen；Globals.STDIN/STDOUT 代替全局默认文件；共享 metatable/methods 表（对齐 C 的 createmeta/luaL_setmetatable） |
| llex.c | `compiler.Lexer` | Token独立类代替SemInfo联合体; switch/length分派关键字; 手动解析十六进制浮点数; HashMap代替anchorstr; ls.fs/ls.dyd移至Lexer消除this.fs歧义; StringBuilder代替MBuffer; save()用ISO-8859-1保留0-255字节 |
| lmathlib.c | `lib.MathLib` | java.lang.Math 代替 C 数学库；frexp/ldexp 用 Double.doubleToLongBits 位操作；xoshiro256** 代替 rand()；randomseed 双种子；pushnumint 实现 lua_numbertointeger 语义 |
| lmem.c | `core.LuaGC` | gray/grayAgain 用侵入式 GrayList 链表（LuaValue.gclist 作 next 指针）对齐 C 的 g->gray 链表 |
| loadlib.c | `lib.PackageLib` | 不支持动态加载.so/.dll(loadlib返回absent); c_searcher仅搜索不加载; loadFile代替luaL_loadfilex; ProcessHandle代替argv[0]; InputStream探测代替fopen |
| lobject.c | `core.LuaDebug` | NameWhat 类替代 C 的 const char **name 输出参数；FindLocal 替代 luaG_findlocal 的 |
| lobject.h | `compiler.SyntaxNodes` | expdesc用类非栈上结构体需copyFrom; Vardesc.ridx用int（C 的 lu_byte 寄存器索引超 127 会溢出）; Vardesc.ctc用expdesc非TValue; KCache类型化HashMap; FuncState.kcache/foldV1/foldV2/foldRes为Java特有优化; Dyndata.actvar用ArrayList |
| lobject.h | `core.LuaBoolean` | C用tag variant区分false/true，Java用boolean字段 |
| lobject.h | `core.LuaCClosure` | Java没有lua_CFunction函数指针；C函数体通过LuaFunction子类/override承载，upvalue槽位按CClosure保存。 |
| lobject.h | `core.LuaClosure` | C中LClosure和CClosure是两个独立结构体；Java合并为LuaClosure+LuaFunction |
| lobject.h | `core.LuaFloat` | C的fltvalue(o)宏直接访问TValue.value_.n，Java用private final double v; NAN/POSINF/NEGINF为Java特有 |
| lobject.h | `core.LuaFunction` | Java用抽象类+子类代替C的三种函数变体(VLCL/VLCF/VCCL) |
| lobject.h | `core.LuaLightUserdata` | C用void*指针，Java用Object引用; C的pvalue(o)宏直接访问指针 |
| lobject.h | `core.LuaNil` | C有VNIL/VEMPTY/VABSTKEY/VNOTABLE四种nil变体，Java只有单一LuaNil |
| lobject.h | `core.LuaNumber` | C用VNUMINT/VNUMFLT tag variant区分整数/浮点，Java用LuaInteger/LuaFloat子类 |
| lobject.h | `core.LuaString` | 短串用独立 byte[]（C 内联 contents[]），shrlen 统一存长度；JVM GC 替代；free 逐对象回调；无外部字符串；短串驻留表持软引用（C 是 strt 上的可回收对象 + luaS_remove 摘链） - |
| lobject.h | `core.LuaTable` | long[] array_numVals+Object[] array_refs+byte[] array_tags(T_* tag)替代Value*+tag* \| null检查替代dummynode \| next绝对下标替代gnext偏移 \| ArrayList替代GCObject链表 \| hashpow2统一 |
| lobject.h | `core.LuaUserdata` | Java用Object代替void*; finalizer注册/GC管理为Java特有 |
| lobject.h | `core.LuaValue` | OO 子类 + tt_ tag; 单一 LuaNil; 无 VSHRSTR/VLNGSTR 分离; LuaFunction/LuaClosure 代替 VLCL/VLCF/VCCL; TOSTRING/METATABLE 为 Java 特有 |
| lobject.h | `core.Prototype` | 无CommonHeader/gclist(JVM GC); 无PF_FIXED(无固定内存); abslineinfo用int[]平铺; is_vararg通过flag位运算; LocVar.ridx为Java特有 |
| lobject.h | `core.UpVal` | parentStack+slot 替代 TValue* 指针；UpVal 非 GC 对象（C 是 GCObject 带 marked） -；luaF_closeupval 的 nw2black+barrier(L,uv,slot) 简化为 barrier(value) |
| lopcodes.h | `compiler.Opcodes` | CREATE_sJ接收已加OFFSET_sJ的sj(C接收原始值); CREATE_ABCk等价CREATE_ABC; opmode用静态方法(C用宏); opName用switch(C用数组) |
| lopcodes.h | `core.BinaryOp` | C 用 OP_* 常量；Java 用枚举统一二元运算码 |
| lopcodes.h | `core.UnaryOp` | C 用 OP_* 常量；Java 用枚举统一一元运算码 |
| loslib.c | `lib.OsLib` | Calendar代替struct tm; nanoTime()代替clock(); Runtime.exec()代替system(); 计数器代替tmpnam(); Locale代替setlocale(collate/ctype不支持); getenv回退到getProperty; remove/rename关联关闭文件句柄 |
| lparser.c | `compiler.Parser` | CodeGen委托代码生成; ls.fs/ls.dyd共享引用; ArrayList+Arrays.copyOf代替luaM_growvector; markupval用传入fs（this.fs 此刻已指向嵌套函数）; Vardesc.ridx/var_vidx用int（lu_byte 溢出）; Lua5.5 global声明; 递归深度保护 |
| lparser.h | `compiler.SyntaxNodes` | expdesc用类非栈上结构体需copyFrom; Vardesc.ridx用int（C 的 lu_byte 寄存器索引超 127 会溢出）; Vardesc.ctc用expdesc非TValue; KCache类型化HashMap; FuncState.kcache/foldV1/foldV2/foldRes为Java特有优化; Dyndata.actvar用ArrayList |
| lstate.c | `core.LuaThread` | 协程 wait/notify（非 longjmp）；GC 用 JVM；nCcalls 拆 nCcalls+nny；openupval 用 ArrayList；错误用 try/catch+LuaError |
| lstate.c | `vm.LuaPlatform` | bareGlobals=lua_newstate, standardGlobals+=luaL_openlibs |
| lstate.h | `core.CallInfo` | u联合体扁平化为独立字段; u2联合体扁平化; savedpc用int索引; func/top用int索引; 直接存closure/thread引用 |
| lstate.h | `core.Globals` | DebugFrame 是 CallInfo 链的调试视图(C 用逐次 lua_getinfo 填 lua_Debug，无常驻等价物); |
| lstate.h | `core.LuaThread` | 协程 wait/notify（非 longjmp）；GC 用 JVM；nCcalls 拆 nCcalls+nny；openupval 用 ArrayList；错误用 try/catch+LuaError |
| lstring.c | `core.LuaString` | 短串用独立 byte[]（C 内联 contents[]），shrlen 统一存长度；JVM GC 替代；free 逐对象回调；无外部字符串；短串驻留表持软引用（C 是 strt 上的可回收对象 + luaS_remove 摘链） - |
| lstrlib.c | `lib.StringFormat` | StringBuilder替代luaL_Buffer; String.format替代sprintf; %g/%G需手动去尾随零; %e/%E指数补零至3位; %a/%A特殊值处理; ByteBuffer替代memcpy; ByteArrayOutputStream替代luaL_Buffer |
| lstrlib.c | `lib.StringLib` | 模式匹配拆分到StringPattern; 格式化拆分到StringFormat; CHAR_TABLE代替ctype; byte[]代替const char* |
| lstrlib.c | `lib.StringPattern` | MatchState 内部类替代栈分配 struct; byte[]+偏移替代 const char*; 异常替代 longjmp; while+continue 模拟 goto; int[] 替代 capture 数组; strFindAux 合并 str_find_aux 与 push_captures |
| ltable.c | `core.LuaTable` | long[] array_numVals+Object[] array_refs+byte[] array_tags(T_* tag)替代Value*+tag* \| null检查替代dummynode \| next绝对下标替代gnext偏移 \| ArrayList替代GCObject链表 \| hashpow2统一 |
| ltablib.c | `lib.TableLib` | 内部类闭包代替 luaL_Reg；geti/seti 复刻 lua_geti/lua_seti 语义；LuaBuffer 代替 luaL_Buffer；int 索引代替 ptrdiff_t |
| ltm.c | `core.Metamethod` | luaT_callTM/luaT_trybinTM/luaT_callorderTM在LuaVM.java中实现（栈操作版本）；缺少maskflags缓存掩码和gfasttm; ordinal()对应LuaTable.flags位号 |
| ltm.c | `vm.LuaIndex` | instanceof+rawget 判断非 tag 系统; rawset 写入非 luaH_finishset; 无移动式 GC 无需锚定表；finishGet 的 val 在 C 是 StkId 指针（目标寄存器），Java 用 int resSlot 直写目标栈位，；避免 JIT 缓存栈数组读取问题；luaG_typeerror 用 stackSlot（原始表位置）给错误信息 |
| lundump.c | `vm.LuaChunk` | OutputStream/InputStream 替代 ZIO, ArrayList 替代 luaM_reallocvector, RuntimeException 替代 longjmp |
| lutf8lib.c | `lib.Utf8Lib` | byte[]代替const char*; encodeUTF8手动实现(Java String不适合无效序列); isCont/isContByte重复(C只有iscont) |
| lutf8lib.c | `lib.Utf8Support` | byte[]替代const char*; int[] result替代指针参数; long处理码点 |
| lvm.c | `vm.LuaArith` | instanceof 分派非 ttypetag switch; long 溢出回绕与 C unsigned 一致；C 用 op_arith/op_arithf 宏(intarith/numarith)分派, Java 用 switch/方法分派；C 的 luaV_mod 用 l_castS2U 检查异号, Java 用 (m^b)<0 功能等价（Java long 有符号） |
| lvm.c | `vm.LuaCompare` | l_strcmp 用字节比较非 strcoll（strings.lua:442 区域排序失败，但 Lua 测试套件不测区域排序）；instanceof 分派非 ttypetag switch; luaV_equalobj 的 ttype/ttypetag 分派简化为 instanceof；C 用 LTnum/LEnum 宏处理整数/浮点比较, Java 用 ltIntFloat/ltFloatInt 等辅助方法，功能等价 |
| lvm.c | `vm.LuaConcat` | C 的 luaV_concat 是栈操作（连接 total 个栈上值）, Java 是双操作数（多值由 LuaVM.concatSharedStack 处理）；C 的 tostring 宏会把数字转为字符串(luaO_tostring), Java 的 canConcatFast 仅检查 String/Number；C 用 luaT_tryconcatTM 处理 __concat, Java 内联调 Metamethod.CONCAT.lookup |
| lvm.c | `vm.LuaIndex` | instanceof+rawget 判断非 tag 系统; rawset 写入非 luaH_finishset; 无移动式 GC 无需锚定表；finishGet 的 val 在 C 是 StkId 指针（目标寄存器），Java 用 int resSlot 直写目标栈位，；避免 JIT 缓存栈数组读取问题；luaG_typeerror 用 stackSlot（原始表位置）给错误信息 |
| lvm.c | `vm.LuaVM` | goto->while+continue \| fastget/set->LuaIndex \| pushclosure+barrier->new LuaClosure；luaF_newtbcupval(delta链表)->直接设置tbclist \| luaH_new+resize+checkGC->new LuaTable；luaF_close->手动遍历openupval/tbclist |

## 二、函数级映射（按 C 源文件分组）

### lapi.c

| C 函数 | Java 位置 |
|---|---|
| `aux_upvalue` | `LuaCClosure#upvalue` |
| `lua_createtable` | `LuaValue#tableOf` |
| `lua_getmetatable` | `LuaBoolean#getmetatable` |
| `lua_isstring` | `LuaString#isstring` |
| `lua_pcallk` | `BaseLib#errfuncRef` |
| `lua_pcallk` | `BaseLib#isMainThread` |
| `lua_pcallk` | `BaseLib#savedErrfuncRef` |
| `lua_pushboolean` | `LuaValue#valueOf` |
| `lua_pushinteger` | `LuaInteger#valueOf` |
| `lua_pushnumber` | `LuaFloat#valueOf` |
| `lua_setmetatable` | `LuaBoolean#setmetatable` |
| `lua_setupvalue` | `LuaCClosure#setupvalue` |
| `lua_upvalueid` | `LuaCClosure#upvalueid` |

### lauxlib.c

| C 函数 | Java 位置 |
|---|---|
| `findfield` | `LuaErrors#findField` |
| `findfield` | `DebugHook#findfield` |
| `lastlevel` | `DebugHook#lastlevel` |
| `luaL_argerror` | `LuaErrors#argError` |
| `luaL_argerror` | `LuaErrors#formatArgError` |
| `luaL_argexpected` | `LuaErrors#argexpected` |
| `luaL_checkany` | `LuaValue#checkany` |
| `luaL_checklstring` | `StringLib#checkStr` |
| `luaL_checknumber` | `LuaErrors#checkDouble` |
| `luaL_checknumber` | `LuaNumber#checknumber` |
| `luaL_error` | `LuaErrors#error` |
| `luaL_execresult` | `OsLib#execResult` |
| `luaL_getmetafield` | `StringLib#getMetaField` |
| `luaL_len` | `TableLib#luaLLen` |
| `luaL_optinteger` | `StringLib#optIntArg` |
| `luaL_optinteger` | `LuaErrors#optLong` |
| `luaL_tolstring` | `BaseLib` |
| `luaL_traceback` | `DebugHook` |
| `luaL_traceback` | `DebugHook#appendFrameHead` |
| `luaL_traceback` | `DebugHook#traceback` |
| `luaL_typeerror` | `LuaErrors#typeError` |
| `luaL_where` | `BaseLib#where` |
| `pushfuncname` | `DebugHook#pushfuncname` |
| `pushglobalfuncname` | `LuaErrors#pushGlobalFuncName` |

### lbaselib.c

| C 函数 | Java 位置 |
|---|---|
| `getMode` | `BaseLib#optJavaString` |
| `ipairsaux` | `BaseLib#INextFn` |
| `ipairsaux` | `LuaTable#inext` |
| `luaB_collectgarbage` | `BaseLib#CollectGarbageFn` |
| `luaB_error` | `BaseLib#err` |
| `luaB_ipairs` | `BaseLib#IPairsFn` |
| `luaB_load` | `LuaPlatform#execute` |
| `luaB_load` | `LuaPlatform#executeChunk` |
| `luaB_load` | `LuaPlatform#preprocessFileChunk` |
| `luaB_next` | `BaseLib#NextFn` |
| `luaB_pcall` | `BaseLib#result` |
| `luaB_select` | `BaseLib` |
| `luaB_setmetatable` | `BaseLib#SetMetatableFn` |
| `luaB_tonumber` | `BaseLib#ToNumberFn` |
| `luaB_xpcall` | `BaseLib#xf` |
| `openResource` | `BaseLib#openResource` |

### lcode.c

| C 函数 | Java 位置 |
|---|---|
| `addk` | `CodeGen#addk` |
| `binopr2TM` | `CodeGen#binopr2TM` |
| `binopr2op` | `CodeGen#binopr2op` |
| `boolF` | `CodeGen#boolF` |
| `boolT` | `CodeGen#boolT` |
| `canToIntegerNS` | `CodeGen#canToIntegerNS` |
| `checkStack` | `CodeGen#checkStack` |
| `code` | `CodeGen#code` |
| `codeABCk` | `CodeGen#codeABC` |
| `codeABCk` | `CodeGen#codeABCk` |
| `codeABRK` | `CodeGen#codeABRK` |
| `codeABx` | `CodeGen#codeABx` |
| `codeAsBx` | `CodeGen#codeAsBx` |
| `codeConcat` | `CodeGen#codeConcat` |
| `codeJump` | `CodeGen#codeJump` |
| `codeK` | `CodeGen#codeK` |
| `codeNil` | `CodeGen#codeNil` |
| `codeRet` | `CodeGen#codeRet` |
| `code_loadbool` | `CodeGen#codeLoadBool` |
| `codearith` | `CodeGen#codearith` |
| `codebinK` | `CodeGen#codebinK` |
| `codebinNoK` | `CodeGen#codebinNoK` |
| `codebinexpval` | `CodeGen#codebinexpval` |
| `codebini` | `CodeGen#codebini` |
| `codebitwise` | `CodeGen#codebitwise` |
| `codecommutative` | `CodeGen#codecommutative` |
| `codeconcat` | `CodeGen#codeconcat` |
| `codeeq` | `CodeGen#codeeq` |
| `codeextraarg` | `CodeGen#codeextraarg` |
| `codenot` | `CodeGen#codenot` |
| `codeorder` | `CodeGen#codeorder` |
| `codesJ` | `CodeGen#codesJ` |
| `codeunexpval` | `CodeGen#codeunexpval` |
| `codevABCk` | `CodeGen#codevABCk` |
| `condjump` | `CodeGen#condjump` |
| `const2val` | `CodeGen#const2val` |
| `constdesc2val` | `CodeGen#constdesc2val` |
| `constfolding` | `CodeGen#constfolding` |
| `discharge2anyreg` | `CodeGen#discharge2anyreg` |
| `discharge2reg` | `CodeGen#discharge2reg` |
| `dischargeVars` | `CodeGen#dischargeVars` |
| `exp2AnyReg` | `CodeGen#exp2AnyReg` |
| `exp2AnyRegup` | `CodeGen#exp2AnyRegup` |
| `exp2NextReg` | `CodeGen#exp2NextReg` |
| `exp2RK` | `CodeGen#exp2RK` |
| `exp2Val` | `CodeGen#exp2Val` |
| `exp2reg` | `CodeGen#exp2reg` |
| `fillidxk` | `CodeGen#fillidxk` |
| `finaltarget` | `CodeGen#finaltarget` |
| `finishbinexpneg` | `CodeGen#finishbinexpneg` |
| `finishbinexpval` | `CodeGen#finishbinexpval` |
| `fitsBx` | `CodeGen#fitsBx` |
| `fitsC` | `CodeGen#fitsC` |
| `fixjump` | `CodeGen#fixjump` |
| `foldbinop` | `CodeGen#foldbinop` |
| `freeexp` | `CodeGen#freeexp` |
| `freeexp_reg` | `CodeGen#freeexp` |
| `freeexps` | `CodeGen#freeexps` |
| `freereg` | `CodeGen#freereg` |
| `freeregs` | `CodeGen#freeexps` |
| `getLabel` | `CodeGen#getLabel` |
| `getjump` | `CodeGen#getjump` |
| `getjumpcontrol` | `CodeGen#getjumpcontrol` |
| `goIfFalse` | `CodeGen#goIfFalse` |
| `goIfTrue` | `CodeGen#goIfTrue` |
| `hasjumps` | `CodeGen#hasjumps` |
| `indexed` | `CodeGen#indexed` |
| `int2sC` | `CodeGen#int2sC` |
| `isCint` | `CodeGen#isCint` |
| `isKint` | `CodeGen#isKint` |
| `isKstr` | `CodeGen#isKstr` |
| `isSCint` | `CodeGen#isSCint` |
| `isSCnumber` | `CodeGen#isSCnumber` |
| `jumponcond` | `CodeGen#jumponcond` |
| `luaK_codeABC` | `CodeGen#codeABC` |
| `luaK_codecheckglobal` | `Parser#codeCheckGlobal` |
| `luaK_exp2K` | `CodeGen#exp2K` |
| `luaK_exp2const` | `Parser#exp2Const` |
| `luaK_finish` | `CodeGen#finish` |
| `luaK_fixline` | `CodeGen#fixline` |
| `luaK_float` | `CodeGen#flt` |
| `luaK_infix` | `CodeGen#infix` |
| `luaK_int` | `CodeGen#intK` |
| `luaK_intK` | `CodeGen#intConst` |
| `luaK_numberK` | `CodeGen#numberK` |
| `luaK_posfix` | `CodeGen#posfix` |
| `luaK_prefix` | `CodeGen#expdesc` |
| `luaK_prefix` | `CodeGen#prefix` |
| `luaK_ret` | `CodeGen#checkLimit` |
| `luaK_self` | `CodeGen` |
| `luaK_self` | `CodeGen#self` |
| `luaK_semerror` | `CodeGen#semError` |
| `luaK_setlist` | `CodeGen#setList` |
| `luaK_settablesize` | `CodeGen#setTableSize` |
| `luaK_vapar2local` | `CodeGen#vapar2local` |
| `need_value` | `CodeGen#needValue` |
| `needvatab` | `CodeGen#needvatab` |
| `negatecondition` | `CodeGen#negatecondition` |
| `nilK` | `CodeGen#nilK` |
| `nvalue` | `CodeGen#nvalue` |
| `patchList` | `CodeGen#patchList` |
| `patchToHere` | `CodeGen#patchToHere` |
| `patchlistaux` | `CodeGen#patchlistaux` |
| `patchtestreg` | `CodeGen#patchtestreg` |
| `previousinstruction` | `CodeGen#previousinstruction` |
| `removelastinstruction` | `CodeGen#removelastinstruction` |
| `removelastlineinfo` | `CodeGen#removelastlineinfo` |
| `removevalues` | `CodeGen#removevalues` |
| `reserveRegs` | `CodeGen#reserveRegs` |
| `savelineinfo` | `CodeGen#savelineinfo` |
| `scnumberIsFloat` | `CodeGen#scnumberIsFloat` |
| `scnumberValue` | `CodeGen#scnumberValue` |
| `setFoldedFloat` | `CodeGen#setFoldedFloat` |
| `setOneRet` | `CodeGen#setOneRet` |
| `setReturns` | `CodeGen#setReturns` |
| `setfltvalue` | `CodeGen#setFltValue` |
| `setivalue` | `CodeGen#setIValue` |
| `storeVar` | `CodeGen#storeVar` |
| `str2K` | `CodeGen#str2K` |
| `stringK` | `CodeGen#stringK` |
| `swapexps` | `CodeGen#swapexps` |
| `toIntegerNS` | `CodeGen#toIntegerNS` |
| `tonumeral` | `CodeGen#tonumeral` |
| `ttisshrstring` | `CodeGen` |
| `unopr2op` | `CodeGen#unopr2op` |
| `validop` | `CodeGen#validop` |

### lcorolib.c

| C 函数 | Java 位置 |
|---|---|
| `auxresume` | `CoroutineLib` |
| `auxstatus` | `LuaThread#auxstatus` |
| `auxstatusStr` | `CoroutineLib#auxstatusStr` |
| `luaB_auxwrap` | `CoroutineLib` |
| `luaB_auxwrap` | `CoroutineLib#CoWrapperFn` |
| `luaB_auxwrap` | `CoroutineLib#arg` |
| `luaB_yield` | `Globals#yield` |
| `luaopen_coroutine` | `CoroutineLib#call` |
| `statname` | `CoroutineLib#statname` |

### ldblib.c

| C 函数 | Java 位置 |
|---|---|
| `db_debug` | `DebugHook#DbDebugFn` |
| `db_gethook` | `DebugHook#DbGetHookFn` |
| `db_getinfo` | `DebugInfo#DbGetInfoFn` |
| `db_getlocal` | `DebugInfo#DbGetLocalFn` |
| `db_getmetatable` | `DebugLib#DbGetMetatableFn` |
| `db_getregistry` | `DebugInfo#DbGetRegistryFn` |
| `db_getupvalue` | `DebugLib#DbGetUpvalueFn` |
| `db_getuservalue` | `DebugLib#DbGetUserValueFn` |
| `db_sethook` | `DebugHook#DbSetHookFn` |
| `db_setlocal` | `DebugInfo#DbSetLocalFn` |
| `db_setmetatable` | `DebugLib#DbSetMetatableFn` |
| `db_setupvalue` | `DebugLib#DbSetUpvalueFn` |
| `db_setuservalue` | `DebugLib#DbSetUserValueFn` |
| `db_traceback` | `DebugHook` |
| `db_traceback` | `DebugHook#DbTracebackFn` |
| `db_upvalueid` | `DebugLib#DbUpvalueIdFn` |
| `db_upvalueid` | `DebugLib#upvalueId` |
| `db_upvaluejoin` | `DebugLib#DbUpvalueJoinFn` |
| `gethooktable` | `DebugHook#getHookTable` |
| `getthread` | `DebugHook#arg` |
| `hookf` | `DebugHook#hookf` |
| `hookmaskToString` | `DebugHook#hookmaskToString` |
| `luaopen_debug` | `DebugLib#call` |
| `resolvehookfunction` | `DebugHook#resolveHookFunction` |
| `stringToHookmask` | `DebugHook#stringToHookmask` |

### ldebug.c

| C 函数 | Java 位置 |
|---|---|
| `activeLines` | `LuaDebug#activeLines` |
| `auxgetinfo` | `DebugInfo#infoFromLevel` |
| `basicgetobjname` | `LuaDebug#basicgetobjname` |
| `changedline` | `LuaVM#MAXIWTHABS` |
| `filterpc` | `LuaDebug#filterpc` |
| `findUpvalue` | `LuaDebug#findUpvalue` |
| `findsetreg` | `LuaDebug#findsetreg` |
| `formatvarinfo` | `LuaDebug#formatvarinfo` |
| `funcnamefromcall` | `LuaDebug#funcnamefromcall` |
| `funcnamefromcode` | `LuaDebug#funcnamefromcode` |
| `getfuncname` | `LuaDebug#getfuncname` |
| `getobjname` | `LuaDebug#getobjname` |
| `getupvalname` | `LuaDebug#getupvalname` |
| `getupvalname` | `UpVal#isOpenAt` |
| `instack` | `LuaDebug#instack` |
| `isEnv` | `LuaDebug#isEnv` |
| `kname` | `LuaDebug#kname` |
| `luaF_getlocalname` | `LuaDebug#getLocalName` |
| `luaG_addinfo` | `LuaErrors#toJavaString` |
| `luaG_callerror` | `LuaErrors#callError` |
| `luaG_concaterror` | `LuaErrors#concatError` |
| `luaG_errormsg` | `LuaError#setPendingError` |
| `luaG_findlocal` | `LuaDebug#findLocal` |
| `luaG_findlocal` | `LuaDebug#findLocalName` |
| `luaG_forerror` | `LuaErrors#forError` |
| `luaG_opinterror` | `LuaErrors#opIntError` |
| `luaG_ordererror` | `LuaErrors#orderError` |
| `luaG_runerror` | `LuaErrors#runError` |
| `luaG_tointerror` | `LuaErrors#toIntError` |
| `luaG_tracecall` | `LuaVM#traceCall` |
| `luaG_traceexec` | `LuaVM#traceCall` |
| `luaG_traceexec` | `LuaVM#traceExec` |
| `luaG_typeerror` | `LuaErrors#typeError` |
| `luaM_error` | `LuaErrors#memError` |
| `lua_Debug` | `DebugInfo#Frame` |
| `lua_getstack` | `Globals#getCallInfoAtLevel` |
| `lua_getstack` | `DebugHook#getstack` |
| `lua_sethook` | `DebugHook` |
| `lua_sethook` | `DebugHook#setHookState` |
| `nextline` | `LuaDebug#nextLine` |
| `rname` | `LuaDebug#rname` |
| `typeerror` | `LuaErrors#typeError` |
| `upvalname` | `LuaDebug#upvalname` |
| `varinfo` | `LuaDebug#varinfo` |
| `varinfo` | `LuaDebug#varinfoAtStack` |

### ldebug.h

| C 函数 | Java 位置 |
|---|---|
| `ci_func` | `CallInfo#ciFuncLua` |

### ldo.c

| C 函数 | Java 位置 |
|---|---|
| `ccall` | `LuaCall` |
| `checkmode` | `LuaErrors#checkModeError` |
| `correctstack` | `LuaVM#correctstack` |
| `default` | `LuaCall#checkStack` |
| `f_parser` | `LuaPlatform#fParser` |
| `genmoveresults` | `LuaCall#genmoveresults` |
| `luaD_call` | `LuaClosure#call` |
| `luaD_call` | `BaseLib#callLua` |
| `luaD_checkminstack` | `LuaGC#checkMinStack` |
| `luaD_closeprotected` | `LuaCall` |
| `luaD_closeprotected` | `LuaVM#closeUpvals` |
| `luaD_closeprotected` | `LuaVM#nCcalls` |
| `luaD_closeprotected` | `LuaVM#oldCi` |
| `luaD_errerr` | `LuaVM#errErr` |
| `luaD_growstack` | `LuaVM#growStack` |
| `luaD_hook` | `LuaVM#HOOKNAMES` |
| `luaD_hookcall` | `LuaVM#hookCall` |
| `luaD_pcall` | `BaseLib#nCcalls` |
| `luaD_pcall` | `BaseLib#shrinkStack` |
| `luaD_poscall` | `LuaCall#poscall` |
| `luaD_precall` | `LuaVM#func` |
| `luaD_precall` | `LuaCall#precall` |
| `luaD_pretailcall` | `LuaVM` |
| `luaD_pretailcall` | `LuaCall#preTailcall` |
| `luaD_pretailcall` | `LuaVM#pretailcallLua` |
| `luaD_protectedparser` | `LuaPlatform#protectedParser` |
| `luaD_rawrunprotected` | `BaseLib#getMessage` |
| `luaD_rawrunprotected` | `BaseLib#nCcalls` |
| `luaD_rawrunprotected` | `BaseLib#normalizeErrorObject` |
| `luaD_rawrunprotected` | `BaseLib#restoreProtectedCallState` |
| `luaD_reallocstack` | `LuaVM#STACKERRSPACE` |
| `luaD_reallocstack` | `LuaVM#reallocStack` |
| `luaD_seterrorobj` | `LuaErrors#errorObject` |
| `luaD_shrinkstack` | `LuaVM` |
| `luaD_shrinkstack` | `LuaVM#shrinkStack` |
| `luaD_throw` | `LuaError#LuaError` |
| `luaD_throw` | `LuaErrors#error` |
| `luaD_throwbaselevel` | `LuaThread` |
| `lua_resume` | `LuaThread#lua_resume` |
| `lua_resume` | `LuaThread#varargsOf` |
| `lua_yieldk` | `LuaThread#lua_yieldk` |
| `moveresults` | `LuaCall#moveresults` |
| `prepCallInfo` | `LuaCall#prepCallInfo` |
| `rethook` | `LuaVM#rethook` |
| `stackinuse` | `LuaVM#stackInUse` |
| `tryfuncTM` | `LuaCall#tryfuncTM` |

### ldo.h

| C 函数 | Java 位置 |
|---|---|
| `luaD_checkstack` | `LuaVM#checkStack` |

### ldump.c

| C 函数 | Java 位置 |
|---|---|
| `abslineinfo` | `LuaChunk#loadInt` |
| `abslineinfo` | `LuaChunk#n` |
| `checkNumInfo` | `LuaChunk#checkNumInfo` |
| `checkNumInfoFloat` | `LuaChunk#checkNumInfoFloat` |
| `lineinfo` | `LuaChunk#loadInt` |
| `lineinfo` | `LuaChunk#n` |
| `locvars` | `LuaChunk#loadInt` |
| `locvars` | `LuaChunk#n` |
| `luaU_dump` | `LuaChunk#dump` |
| `luaU_dumpAlign` | `LuaChunk#dumpAlign` |
| `luaU_dumpBlock` | `LuaChunk#dumpBlock` |
| `luaU_dumpByte` | `LuaChunk#dumpByte` |
| `luaU_dumpByte` | `LuaChunk#writeByte` |
| `luaU_dumpInt` | `LuaChunk#dumpInt` |
| `luaU_dumpInteger` | `LuaChunk#dumpInteger` |
| `luaU_dumpNumber` | `LuaChunk#dumpNumber` |
| `luaU_dumpNumber` | `LuaChunk#writeNumInfo` |
| `luaU_dumpSize` | `LuaChunk#dumpSize` |
| `luaU_dumpString` | `LuaChunk#dumpString` |
| `luaU_dumpVarint` | `LuaChunk#dumpVarint` |

### lfunc.c

| C 函数 | Java 位置 |
|---|---|
| `checkclosemth` | `LuaVM#getTmByObj` |
| `luaF_close` | `LuaVM#closeUpvals` |
| `luaF_closeupval` | `UpVal#closeUpval` |
| `luaF_findupval` | `LuaVM#findOrCreateOpenUpval` |
| `luaF_initupvals` | `UpVal#UpVal` |
| `luaF_initupvals` | `UpVal#closedOf` |
| `luaF_initupvals` | `LuaPlatform#newLuaClosure` |
| `luaF_newCclosure` | `LuaCClosure#LuaCClosure` |
| `luaF_newLclosure` | `LuaClosure#LuaClosure` |
| `luaF_newproto` | `Prototype#commitProtoMem` |
| `luaF_newtbcupval` | `LuaVM#newTbcUpval` |
| `luaF_newtbcupval` | `UpVal#tbc` |
| `newupval` | `UpVal#UpVal` |

### lfunc.h

| C 函数 | Java 位置 |
|---|---|
| `upisopen` | `UpVal#upisopen` |
| `uplevel` | `UpVal#slot` |

### lgc.c

| C 函数 | Java 位置 |
|---|---|
| `GCScallfin` | `LuaTable` |
| `GCTM` | `LuaTable` |
| `GCTM` | `LuaTable#runPendingFinalizers` |
| `atomic` | `LuaGC#addAll` |
| `atomic` | `LuaGC#atomicWeakAndFinalizers` |
| `atomic` | `LuaGC#detach` |
| `atomic` | `LuaGC#flipwhiteInternal` |
| `callfin` | `LuaTable#callFinalizers` |
| `clearbykeys` | `LuaTable#clearByKeys` |
| `clearbyvalues` | `LuaTable#clearByValues` |
| `cleargraylists` | `LuaGC#cleargraylists` |
| `convergeephemerons` | `LuaTable#convergeEphemeron` |
| `correctgraylist` | `LuaTable` |
| `correctgraylist` | `LuaTable#gcAge` |
| `correctgraylists` | `LuaGC#correctGrayLists` |
| `finishfullgc` | `LuaTable#repropagateAll` |
| `finishgencycle` | `LuaGC` |
| `fullinc` | `LuaGC` |
| `fullinc` | `LuaGC#cleargraylists` |
| `g` | `LuaGC` |
| `getgclist` | `LuaValue#gclist` |
| `getmode` | `LuaTable#weakMode` |
| `incstep` | `LuaGC#fast` |
| `incstep` | `LuaGC#incstep` |
| `incstep` | `LuaGC#modeName` |
| `iscleared` | `LuaTable#iscleared` |
| `iscollectable` | `LuaTable#t` |
| `luaC_barrier_` | `LuaGC` |
| `luaC_barrierback` | `LuaTable` |
| `luaC_barrierback_` | `LuaGC` |
| `luaC_barrierback_` | `LuaGC#barrierback` |
| `luaC_changemode` | `LuaGC#changeMode` |
| `luaC_checkfinalizer` | `LuaTable#registerFinalizer` |
| `luaC_fullgc` | `LuaGC#fullGC` |
| `luaC_fullgc` | `LuaGC#fullGCCaller` |
| `luaC_objbarrier` | `LuaTable` |
| `luaC_runtilstate` | `LuaGC#runToState` |
| `luaC_step` | `LuaGC` |
| `luaC_step` | `LuaGC#gcStep` |
| `markbeingfnz` | `LuaTable#GrayList` |
| `markkey` | `LuaTable#ktt` |
| `markold` | `LuaGC#markOld` |
| `objsize` | `LuaClosure#gcSize` |
| `propagateall` | `LuaTable#propagateGray` |
| `restartcollection` | `LuaGC#cleargraylists` |
| `restartcollection` | `LuaTable#markRoots` |
| `setminordebt` | `LuaGC#setminordebt` |
| `setpause` | `LuaGC#setpause` |
| `singlestep` | `LuaGC#singlestep` |
| `sweep` | `LuaThread#sweepByColor` |
| `sweep` | `LuaClosure#sweepClosuresByColor` |
| `sweep` | `LuaTable#sweepDeadTables` |
| `sweep` | `LuaFunction#sweepFunctionsByColor` |
| `sweep` | `Prototype#sweepProtosByColor` |
| `sweep2old` | `LuaTable#agesAfterFullGC` |
| `sweep2old` | `LuaString#resetColorsAfterFullGC` |
| `sweep2old` | `LuaGC#sweepByColor` |
| `sweepgen` | `LuaUserdata` |
| `sweepgen` | `LuaTable#agesAfterStepGC` |
| `sweepgen` | `LuaGC#isWhite` |
| `sweepgen` | `LuaTable#makeWhite` |
| `sweepgen` | `LuaClosure#sweepGen` |
| `traverseCclosure` | `LuaCClosure#gcRefs` |
| `traverseLclosure` | `LuaTable` |
| `traversearray` | `LuaTable#marked` |
| `traverseephemeron` | `LuaTable#traverseEphemeron` |
| `traverseproto` | `LuaTable` |
| `traverseproto` | `LuaTable#markSubProto` |
| `traversestrongtable` | `LuaTable` |
| `traversetable` | `LuaTable#markTableContents` |
| `traversethread` | `LuaTable` |
| `traversethread` | `LuaTable#markThreadFrames` |
| `traverseweakvalue` | `LuaTable#traverseWeakValue` |
| `tryagain` | `LuaGC#runningGlobalsForGC` |
| `tryagain` | `LuaGC#tryagain` |
| `tryagain` | `LuaGC#tryagainN` |
| `youngcollection` | `LuaGC#youngCollection` |

### lgc.h

| C 函数 | Java 位置 |
|---|---|
| `GCSphases` | `LuaGC#GCSpropagate` |
| `iscollectable` | `LuaTable#isWeakCollectable` |
| `luaC_barrierback` | `LuaIndex#barrierback` |

### linit.c

| C 函数 | Java 位置 |
|---|---|
| `luaL_openlibs` | `LuaStandard#standardGlobals` |

### liolib.c

| C 函数 | Java 位置 |
|---|---|
| `aux_close` | `IoFile#close` |
| `checkHandle` | `IoLib#checkHandle` |
| `createmeta` | `IoLib#createmeta` |
| `f_close` | `IoLib#f_close` |
| `f_flush` | `IoLib#f_flush` |
| `f_flush` | `IoFile#flush` |
| `f_gc` | `IoLib#f_gc` |
| `f_lines` | `IoLib#f_lines` |
| `f_lines` | `IoFile#lines` |
| `f_read` | `IoLib#f_read` |
| `f_seek` | `IoLib#f_seek` |
| `f_seek` | `IoFile#seek` |
| `f_setvbuf` | `IoLib#f_setvbuf` |
| `f_setvbuf` | `IoFile#setvbuf` |
| `f_tostring` | `IoLib#f_tostring` |
| `f_write` | `IoLib#f_write` |
| `g_read` | `IoFile#read` |
| `g_write` | `IoFile#write` |
| `io_open` | `IoLib#ioopen` |
| `ioclose` | `IoLib#ioclose` |
| `ioflush` | `IoLib#ioflush` |
| `ioinput` | `IoLib#ioinput` |
| `iolines` | `IoLib#iolines` |
| `iooutput` | `IoLib#iooutput` |
| `iopopen` | `IoLib#iopopen` |
| `ioread` | `IoLib#ioread` |
| `iotmpfile` | `IoLib#iotmpfile` |
| `iotype` | `IoLib#iotype` |
| `iowrite` | `IoLib#iowrite` |
| `luaL_Stream` | `IoFile#IoFileHandle` |
| `luaL_checkudata` | `IoLib#checkfile` |
| `luaopen_io` | `IoLib#call` |
| `read_line` | `IoFile#readLine` |
| `read_number` | `IoFile#readNumber` |
| `tofile` | `IoLib#unwrapHandle` |

### llex.c

| C 函数 | Java 位置 |
|---|---|
| `check_next1` | `Lexer#checkNext1` |
| `check_next2` | `Lexer#checkNext2` |
| `hexval` | `Lexer#hexDigit` |
| `inclinenumber` | `Lexer#incline` |
| `l_str2d` | `Lexer#isValidDecimalFloatSyntax` |
| `l_str2int` | `Lexer#parseDecimalInteger` |
| `lexerror` | `Lexer#lexerror` |
| `lislalnum` | `Lexer#islalnum` |
| `lislalpha` | `Lexer#islalpha` |
| `lisxdigit` | `Lexer#isxdigit` |
| `llex` | `Lexer#llex` |
| `luaX_lookahead` | `Lexer#lookAhead` |
| `luaX_new` | `Lexer#Lexer` |
| `luaX_newstring` | `Lexer#newString` |
| `luaX_next` | `Lexer#nextToken` |
| `luaX_syntaxerror` | `Lexer#syntaxError` |
| `luaX_syntaxerrorAtLine` | `Lexer#syntaxErrorAtLine` |
| `luaX_token2str` | `Lexer` |
| `luaX_token2str` | `Lexer#tokenName` |
| `read_long_string` | `Lexer#readLongString` |
| `read_numeral` | `Lexer` |
| `read_numeral` | `Lexer#readNumeral` |
| `read_string` | `Lexer#readString` |
| `readdecesc` | `Lexer#readDecEsc` |
| `readhexaesc` | `Lexer#readHexEsc` |
| `readutf8esc` | `Lexer` |
| `save` | `Lexer#save` |
| `save_and_next` | `Lexer#saveAndNext` |
| `skip_sep` | `Lexer#skipSep` |
| `txtToken` | `Lexer#txtToken` |
| `utf8esc` | `Lexer#readUtf8Esc` |
| `zgetc` | `Lexer#nextChar` |

### lmathlib.c

| C 函数 | Java 位置 |
|---|---|
| `luaopen_math` | `MathLib#call` |
| `math_abs` | `MathLib#AbsFn` |
| `math_acos` | `MathLib#AcosFn` |
| `math_asin` | `MathLib#AsinFn` |
| `math_atan` | `MathLib#atan` |
| `math_ceil` | `MathLib#CeilFn` |
| `math_cos` | `MathLib#CosFn` |
| `math_deg` | `MathLib#DegFn` |
| `math_exp` | `MathLib#ExpFn` |
| `math_floor` | `MathLib#FloorFn` |
| `math_fmod` | `MathLib#FmodFn` |
| `math_frexp` | `MathLib#FrexpFn` |
| `math_ldexp` | `MathLib#LdexpFn` |
| `math_log` | `MathLib#LogFn` |
| `math_max` | `MathLib#MaxFn` |
| `math_min` | `MathLib#MinFn` |
| `math_modf` | `MathLib#ModfFn` |
| `math_rad` | `MathLib#RadFn` |
| `math_random` | `MathLib#RandomFn` |
| `math_randomseed` | `MathLib#RandomSeedFn` |
| `math_sin` | `MathLib#SinFn` |
| `math_sqrt` | `MathLib#SqrtFn` |
| `math_tan` | `MathLib#TanFn` |
| `math_tointeger` | `MathLib#ToIntegerFn` |
| `math_type` | `MathLib#MathTypeFn` |
| `math_ult` | `MathLib#UltFn` |
| `pushnumint` | `MathLib#pushnumint` |

### lmem.c

| C 函数 | Java 位置 |
|---|---|
| `firsttry` | `LuaGC#firsttryN` |
| `firsttry` | `LuaGC#firsttryProcess` |
| `luaM_free_` | `LuaGC#free` |
| `luaM_toobig` | `LuaErrors#tooBig` |

### loadlib.c

| C 函数 | Java 位置 |
|---|---|
| `ll_loadlib` | `PackageLib#LoadLibFn` |
| `ll_require` | `PackageLib#require` |
| `ll_searchpath` | `PackageLib#searchpath` |
| `luaopen_package` | `PackageLib#call` |
| `noenv` | `PackageLib#noenv` |
| `searcher_C` | `PackageLib#c_searcher` |
| `searcher_Croot` | `PackageLib#croot_searcher` |
| `searcher_Lua` | `PackageLib#lua_searcher` |
| `searcher_preload` | `PackageLib#preload_searcher` |
| `setpath` | `PackageLib#setpath` |
| `setprogdir` | `PackageLib#setprogdir` |

### lobject.c

| C 函数 | Java 位置 |
|---|---|
| `LUA_IDSIZE` | `LuaDebug#LUA_IDSIZE` |
| `intarith` | `LuaArith#rawIntArith` |
| `luaO_ceillog2` | `CodeGen#ceilLog2` |
| `luaO_chunkid` | `Lexer#chunkId` |
| `luaO_chunkid` | `LuaDebug#chunkid` |
| `luaO_rawarith` | `CodeGen#rawarith` |
| `luaO_str2num` | `Lexer#parseHexFloat` |
| `luaO_str2num` | `LuaString#scannumber` |
| `luaO_str2num` | `LuaString#scannumberDefault` |
| `numarith` | `LuaArith#rawNumArith` |

### lobject.h

| C 函数 | Java 位置 |
|---|---|
| `LUA_VEMPTY` | `LuaTable#vempty` |
| `getudatamem` | `LuaValue#touserdata` |
| `iscollectable` | `LuaValue#iscollectable` |
| `isvararg` | `Prototype#isVararg` |
| `l_isfalse` | `LuaBoolean#toboolean` |
| `luaC_checkGC` | `LuaClosure` |
| `luaF_newproto` | `Parser#commitProtoMem` |
| `luaL_checkboolean` | `LuaBoolean#checkboolean` |
| `luaL_optboolean` | `LuaBoolean#optboolean` |
| `luaL_optvalue` | `LuaNil#optvalue` |
| `luaT_gettmbyobj` | `LuaValue#metaTag` |
| `luaV_rawequalobj` | `LuaBoolean#raweq` |
| `luaV_tonumber` | `LuaNumber#tonumber` |
| `luaV_tostring` | `LuaFloat#strValue` |
| `luaV_tostring` | `LuaValue#tostring` |
| `lua_newuserdata` | `LuaValue#userdataOf` |
| `lua_objlen` | `LuaValue#rawlen` |
| `lua_pushlstring` | `LuaValue#valueOf` |
| `lua_toclosure` | `LuaFunction#checkclosure` |
| `pvalue` | `LuaLightUserdata#touserdata` |
| `sizeudata` | `LuaUserdata#userdataStorageBytes` |
| `tagisfalse` | `LuaVM#tagisfalse` |
| `tsvalue` | `LuaValue#strValue` |
| `tt_` | `LuaValue#tt_` |
| `ttisCclosure` | `LuaValue#isCclosure` |
| `ttisLclosure` | `LuaValue#isLclosure` |
| `ttisboolean` | `LuaBoolean#isboolean` |
| `ttisfloat` | `LuaValue#isfloat` |
| `ttisfulluserdata` | `LuaValue#isfulluserdata` |
| `ttisfunction` | `LuaValue#isfunction` |
| `ttisinteger` | `LuaInteger#todouble` |
| `ttislcf` | `LuaValue#islcf` |
| `ttislightuserdata` | `LuaLightUserdata#islightuserdata` |
| `ttisnil` | `LuaNil#isnil` |
| `ttisnumber` | `LuaValue#isNumberTag` |
| `ttistable` | `LuaValue#istable` |
| `ttisthread` | `LuaValue#isthread` |
| `ttisuserdata` | `LuaValue#isuserdata` |
| `ttype` | `LuaBoolean#type` |
| `uplevel` | `UpVal#get` |

### lopcodes.h

| C 函数 | Java 位置 |
|---|---|
| `ABSLINEINFO` | `LuaDebug#ABSLINEINFO` |
| `getOpMode` | `Opcodes#getOpMode` |
| `int2sC` | `Opcodes#int2sC` |
| `luaP_opmodes` | `Opcodes#opModes` |
| `luaP_opnames` | `Opcodes#opName` |
| `opmode` | `Opcodes#opmode` |
| `testAMode` | `Opcodes#testAMode` |
| `testMMMode` | `Opcodes#testMMMode` |
| `testTMode` | `Opcodes#testTMode` |

### loslib.c

| C 函数 | Java 位置 |
|---|---|
| `getepochtime` | `OsLib#currenttime` |
| `getfield` | `OsLib#getDateField` |
| `luaopen_os` | `OsLib#call` |
| `os_clock` | `OsLib#os_clock` |
| `os_date` | `OsLib#os_date` |
| `os_difftime` | `OsLib#os_difftime` |
| `os_execute` | `OsLib#os_execute` |
| `os_exit` | `OsLib#os_exit` |
| `os_getenv` | `OsLib#os_getenv` |
| `os_remove` | `OsLib#os_remove` |
| `os_rename` | `OsLib#os_rename` |
| `os_setlocale` | `OsLib#os_setlocale` |
| `os_time` | `OsLib#os_time` |
| `os_tmpname` | `OsLib#os_tmpname` |
| `setallfields` | `OsLib#setDateFields` |
| `strftime` | `OsLib#formatDate` |

### lparser.c

| C 函数 | Java 位置 |
|---|---|
| `addprototype` | `Parser#addprototype` |
| `adjust_assign` | `Parser#adjustAssign` |
| `adjustlocalvars` | `Parser#adjustlocalvars` |
| `allocupvalue` | `Parser#allocupvalue` |
| `block` | `Parser#block` |
| `block_follow` | `Parser#blockFollow` |
| `body` | `Parser#body` |
| `breakstat` | `Parser#breakstat` |
| `buildglobal` | `Parser#buildglobal` |
| `buildvar` | `Parser#buildvar` |
| `check` | `Parser#check` |
| `check_conflict` | `Parser#checkConflict` |
| `check_match` | `Parser#checkMatch` |
| `check_readonly` | `Parser#checkReadonly` |
| `checkglobal` | `Parser#checkglobal` |
| `checknext` | `Parser#checknext` |
| `checkrepeated` | `Parser#checkrepeated` |
| `checktoclose` | `Parser#checktoclose` |
| `close_func` | `Parser#closeFunc` |
| `closegoto` | `Parser#closegoto` |
| `closelistfield` | `Parser#closelistfield` |
| `codeclosure` | `Parser#codeclosure` |
| `codename` | `Parser#codename` |
| `codestring` | `Parser#codestring` |
| `cond` | `Parser#cond` |
| `constructor` | `Parser#constructor` |
| `createlabel` | `Parser#createlabel` |
| `enterblock` | `Parser#enterblock` |
| `error_expected` | `Parser#errorExpected` |
| `errorlimit` | `Parser#errorlimit` |
| `exp1` | `Parser#exp1` |
| `explist` | `Parser#explist` |
| `expr` | `Parser#expr` |
| `exprstat` | `Parser#exprstat` |
| `field` | `Parser#field` |
| `fieldsel` | `Parser#fieldsel` |
| `findlabel` | `Parser#findlabel` |
| `fixforjump` | `Parser#fixforjump` |
| `forbody` | `Parser#forbody` |
| `forlist` | `Parser#forlist` |
| `fornum` | `Parser#fornum` |
| `forstat` | `Parser#forstat` |
| `funcargs` | `Parser#funcargs` |
| `funcargs` | `Parser#linenumber` |
| `funcname` | `Parser#funcname` |
| `funcstat` | `Parser#funcstat` |
| `getbinopr` | `Parser#getbinopr` |
| `getglobalattribute` | `Parser#getglobalattribute` |
| `getlocalvardesc` | `Parser#getlocalvardesc` |
| `getunopr` | `Parser#getunopr` |
| `getvarattribute` | `Parser#getvarattribute` |
| `globalfunc` | `Parser#globalfunc` |
| `globalnames` | `Parser#globalnames` |
| `globalstat` | `Parser#globalstat` |
| `globalstatfunc` | `Parser#globalstatfunc` |
| `gotostat` | `Parser#gotostat` |
| `hasmultret` | `Parser#hasmultret` |
| `ifstat` | `Parser#ifstat` |
| `init_exp` | `SyntaxNodes#init` |
| `init_exp` | `Parser#initExp` |
| `init_var` | `Parser#initVar` |
| `initglobal` | `Parser#initglobal` |
| `jumpscopeerror` | `Parser#jumpscopeerror` |
| `labelstat` | `Parser#labelstat` |
| `lastlistfield` | `Parser#lastlistfield` |
| `leaveblock` | `Parser#leaveblock` |
| `listfield` | `Parser#listfield` |
| `localdebuginfo` | `Parser#localdebuginfo` |
| `localfunc` | `Parser#localfunc` |
| `localstat` | `Parser#localstat` |
| `luaY_checklimit` | `Parser#checkLimit` |
| `luaY_nvarstack` | `Parser#nVarStack` |
| `luaY_nvarstack` | `CodeGen#nvarstack` |
| `luaY_parser` | `Parser#Parser` |
| `luaY_parser` | `Parser#parse` |
| `mainfunc` | `Parser#mainfunc` |
| `marktobeclosed` | `Parser#marktobeclosed` |
| `markupval` | `Parser#markupval` |
| `maxtostore` | `Parser#maxtostore` |
| `new_localvar` | `Parser#newLocalvar` |
| `new_varkind` | `Parser#newVarkind` |
| `newgotoentry` | `Parser#newgotoentry` |
| `newlabelentry` | `Parser#newlabelentry` |
| `newupvalue` | `Parser#newupvalue` |
| `open_func` | `Parser#openFunc` |
| `parlist` | `Parser#parlist` |
| `primaryexp` | `Parser#primaryexp` |
| `recfield` | `Parser#recfield` |
| `registerlocalvar` | `Parser#registerlocalvar` |
| `reglevel` | `Parser#reglevel` |
| `removevars` | `Parser#removevars` |
| `repeatstat` | `Parser#repeatstat` |
| `restassign` | `Parser#restassign` |
| `retstat` | `Parser#retstat` |
| `searchupvalue` | `Parser#searchupvalue` |
| `searchvar` | `Parser#searchvar` |
| `setvararg` | `Prototype#setVararg` |
| `setvararg` | `Parser#setvararg` |
| `simpleexp` | `Parser#simpleexp` |
| `singlevar` | `Parser#singlevar` |
| `singlevaraux` | `Parser#singlevaraux` |
| `solvegotos` | `Parser#solvegotos` |
| `statement` | `Parser#statement` |
| `statlist` | `Parser#statlist` |
| `storevartop` | `Parser#storevartop` |
| `str_checkname` | `Parser#strCheckname` |
| `subexpr` | `Parser#subexpr` |
| `suffixedexp` | `Parser#suffixedexp` |
| `test_then_block` | `Parser#testThenBlock` |
| `testnext` | `Parser#testnext` |
| `undefgoto` | `Parser#undefgoto` |
| `vkisvar` | `Parser#vkisvar` |
| `whilestat` | `Parser#whilestat` |
| `yindex` | `Parser#yindex` |

### lstate.c

| C 函数 | Java 位置 |
|---|---|
| `init_registry` | `LuaThread#setEntry` |
| `l_newthread` | `LuaThread#threadSize` |
| `luaE_checkcstack` | `LuaCall#checkCStack` |
| `luaE_checkcstack` | `LuaCall#checkCStackPublic` |
| `luaE_freethread` | `LuaThread` |
| `luaE_freethread` | `LuaThread#closeFromCollector` |
| `luaE_freethread` | `LuaThread#threadSize` |
| `luaE_resetthread` | `LuaThread#func` |
| `luaE_setdebt` | `LuaGC#setDebt` |
| `luaE_threadsize` | `LuaThread#threadSize` |
| `luaE_warnerror` | `Globals#warnerror` |
| `luaE_warning` | `Globals#warning` |
| `lua_closethread` | `LuaThread#lua_closethread` |
| `lua_newstate` | `LuaPlatform#bareGlobals` |
| `lua_newthread` | `LuaThread#LuaThread` |
| `lua_newthread` | `DebugHook#inheritHooks` |
| `stack_init` | `LuaThread#initStack` |

### lstate.h

| C 函数 | Java 位置 |
|---|---|
| `allowhook` | `LuaThread#allowhook` |
| `base_ci` | `LuaThread#base_ci` |
| `basehookcount` | `LuaThread#basehookcount` |
| `ci` | `LuaThread#ci` |
| `ftransfer` | `LuaThread#ftransfer` |
| `getCcalls` | `Globals#getNCcalls` |
| `hook` | `LuaThread#hook` |
| `hookcount` | `LuaThread#hookcount` |
| `hookmask` | `LuaThread#hookmask` |
| `l_G` | `LuaThread#l_G` |
| `nCcalls` | `LuaThread#nCcalls` |
| `nci` | `LuaThread#nci` |
| `nny` | `Globals#getNny` |
| `nny` | `Globals#setNny` |
| `ntransfer` | `LuaThread#ntransfer` |
| `oldpc` | `LuaThread#oldpc` |
| `openupval` | `LuaThread#openupval` |
| `setCcalls` | `Globals#setNCcalls` |
| `stack` | `LuaThread#stack` |
| `stack_last` | `LuaThread#stack_last` |
| `status` | `LuaThread#status` |
| `tbclist` | `LuaThread#tbclist` |
| `top` | `LuaThread#top` |

### lstring.c

| C 函数 | Java 位置 |
|---|---|
| `createstrobj` | `LuaString#createstrobj` |
| `getmetatable` | `LuaString#getmetatable` |
| `internshrstr` | `LuaString#internShort` |
| `l_str2dloc` | `LuaString#mayNeedLocaleDecimalFloat` |
| `luaL_checkstring` | `LuaString#checkstring` |
| `luaL_optlstring` | `LuaString#optJavaString` |
| `luaS_clearcache` | `LuaString#clearStrCacheByColor` |
| `luaS_createlngst` | `LuaString#LuaString` |
| `luaS_eqstr` | `LuaString#equals` |
| `luaS_hash` | `LuaString#hashCode` |
| `luaS_hashlongstr` | `LuaString#hashCode` |
| `luaS_init` | `LuaString#fixedLiteral` |
| `luaS_new` | `LuaString#newStr` |
| `luaS_newlstr` | `LuaString#newLstr` |
| `luaS_resize` | `LuaString#resizeShortStringTable` |
| `luaS_sizelngstr` | `LuaString#sizeLngStr` |
| `luaT_objtypename` | `LuaString#typeName` |
| `luaV_rawequalobj` | `LuaString#equals` |
| `luaV_rawequalobj` | `LuaString#raweq` |
| `luaV_rawlen` | `LuaString#rawlen` |
| `luaV_tonumber` | `LuaString#isnumber` |
| `luaV_tonumber` | `LuaString#tonumber` |
| `luaV_tostring` | `LuaString#tostring` |
| `setmetatable` | `LuaString#setmetatable` |
| `sizestrshr` | `LuaString#sizeStrShr` |
| `tsvalue` | `LuaString#strValue` |
| `ttypetag` | `LuaString#type` |

### lstrlib.c

| C 函数 | Java 位置 |
|---|---|
| `GMatchState` | `StringLib#GMatchState` |
| `MatchState` | `StringPattern#MatchState` |
| `add_s` | `StringPattern` |
| `add_value` | `StringPattern` |
| `add_value` | `StringPattern#addValue` |
| `addliteral` | `StringFormat#literalString` |
| `arith` | `StringLib#ArithFn` |
| `arith_unm` | `StringLib#ArithUnmFn` |
| `captureToClose` | `StringPattern#captureToClose` |
| `check_capture` | `StringPattern#checkCapture` |
| `classend` | `StringPattern#classend` |
| `dflt` | `StringPattern#dflt` |
| `endCapture` | `StringPattern#endCapture` |
| `formatHexFloat` | `StringFormat#formatHexFloat` |
| `get_onecapture` | `StringPattern` |
| `getdetails` | `StringFormat#getdetails` |
| `getformat` | `StringFormat` |
| `getformat` | `StringFormat#precDigits` |
| `getnum` | `StringFormat#getnum` |
| `getnumlimit` | `StringFormat#getnumlimit` |
| `getoption` | `StringFormat#getoption` |
| `gmatch` | `StringLib#initPos` |
| `gmatch` | `StringLib#tolong` |
| `gmatch_aux` | `StringLib#GmatchAuxFn` |
| `gmatch_aux` | `StringPattern#gmatchNext` |
| `hexDigit` | `StringPattern#hexDigit` |
| `isPositionCap` | `StringPattern#isPositionCap` |
| `isalpha` | `StringPattern#isalpha` |
| `lmemfind` | `StringPattern#ISO_8859_1` |
| `match` | `StringPattern#match` |
| `matchBalance` | `StringPattern#matchBalance` |
| `matchBracketClass` | `StringPattern#matchBracketClass` |
| `matchCapture` | `StringPattern#matchCapture` |
| `matchClass` | `StringPattern#matchClass` |
| `match_capture` | `StringPattern` |
| `maxExpand` | `StringPattern#maxExpand` |
| `minExpand` | `StringPattern#minExpand` |
| `noSpecials` | `StringPattern#noSpecials` |
| `packint` | `StringFormat#packint` |
| `padExponent` | `StringFormat#padExponent` |
| `posrelat` | `StringLib#posrelat` |
| `prepstate` | `StringLib#MatchState` |
| `prepstate` | `StringPattern#acquireGsubMS` |
| `prepstate` | `StringPattern#prepstate` |
| `prepstate` | `StringPattern#srcBytes` |
| `quotefloat` | `StringFormat#leadingBit` |
| `reprepstate` | `StringPattern#reprepstate` |
| `singlematch` | `StringPattern#singlematch` |
| `startCapture` | `StringPattern#startCapture` |
| `strFormat` | `StringFormat#strFormat` |
| `strFormat` | `StringFormat#strFormatBB` |
| `strFormat` | `StringFormat#strFormatSB` |
| `strMatch` | `StringPattern#strMatch` |
| `strPack` | `StringFormat#strPack` |
| `strPackSize` | `StringFormat#strPackSize` |
| `strUnpack` | `StringFormat#strUnpack` |
| `str_byte` | `StringLib#optIntArg` |
| `str_byte` | `StringLib#posrelat` |
| `str_char` | `StringLib#CharFn` |
| `str_char` | `StringLib#checkLong` |
| `str_dump` | `StringLib#DumpFn` |
| `str_dump` | `StringLib#arg` |
| `str_find` | `StringLib#FindFn` |
| `str_find_aux` | `StringPattern#optLong` |
| `str_find_aux` | `StringPattern#strFindAux` |
| `str_format` | `StringFormat` |
| `str_format` | `StringLib#FormatFn` |
| `str_format` | `StringFormat#LuaError` |
| `str_format` | `StringFormat#append` |
| `str_format` | `StringFormat#checkDouble` |
| `str_format` | `StringFormat#checkLong` |
| `str_format` | `StringFormat#sprintfFloat` |
| `str_format` | `StringFormat#validateFormatSpec` |
| `str_gmatch` | `StringLib#GmatchFn` |
| `str_gsub` | `StringLib#GsubFn` |
| `str_gsub` | `StringPattern#isnil` |
| `str_gsub` | `StringPattern#strGsub` |
| `str_len` | `StringLib#LenFn` |
| `str_lower` | `StringLib` |
| `str_lower` | `StringLib#LowerFn` |
| `str_match` | `StringLib#MatchFn` |
| `str_pack` | `StringLib#PackFn` |
| `str_packsize` | `StringFormat` |
| `str_packsize` | `StringLib#PackSizeFn` |
| `str_rep` | `StringLib#RepFn` |
| `str_reverse` | `StringLib#ReverseFn` |
| `str_sub` | `StringLib#SubFn` |
| `str_toutf8` | `StringLib#ToUtf8Fn` |
| `str_unpack` | `StringLib#UnpackFn` |
| `str_upper` | `StringLib` |
| `str_upper` | `StringLib#UpperFn` |
| `stripGZeroes` | `StringFormat#stripGZeroes` |
| `tolstringValue` | `StringFormat#tolstringValue` |
| `tonum` | `StringLib#tonum` |
| `trymt` | `StringLib#opname` |
| `trymt` | `StringLib#runErrorWithInfo` |
| `trymt` | `StringLib#trymt` |
| `unpackInt` | `StringFormat#unpackInt` |
| `validateFormatSpec` | `StringFormat#validateFormatSpec` |

### ltable.c

| C 函数 | Java 位置 |
|---|---|
| `concretesize` | `LuaTable#arrayBytes` |
| `finishnodeset` | `LuaTable#finishnodesetGeneric` |
| `hash_search` | `LuaTable#hashSearch` |
| `hashmod` | `LuaTable#hashmod` |
| `hashpow2` | `LuaTable#hashpow2` |
| `insertkey` | `LuaTable` |
| `insertkey` | `LuaTable#empty` |
| `insertkey` | `LuaTable#mainposition` |
| `invalidateTMcache` | `LuaTable#invalidateTMcache` |
| `isabstkey` | `LuaTable#error` |
| `isdummy` | `LuaTable#insertHash` |
| `keyinarray` | `LuaTable` |
| `keyisdead` | `LuaTable#keyisdead` |
| `l_hashfloat` | `LuaTable#hashFloat` |
| `luaH_finishset` | `LuaTable#finishSet` |
| `luaH_get` | `LuaTable#hashGet` |
| `luaH_get` | `LuaValue#rawget` |
| `luaH_getint` | `LuaTable#getInt` |
| `luaH_new` | `LuaTable#LuaTable` |
| `luaH_newkey` | `LuaTable` |
| `luaH_newkey` | `LuaTable#newKey` |
| `luaH_next` | `LuaValue#next` |
| `luaH_next` | `LuaTable#nextEntry` |
| `luaH_pset` | `LuaTable#pset` |
| `luaH_psetint` | `LuaTable` |
| `luaH_psetshortstr` | `LuaTable` |
| `luaH_psetshortstr` | `LuaTable#fastSetShortStr` |
| `luaH_resize` | `LuaTable` |
| `luaH_resize` | `LuaTable#resize` |
| `luaH_set` | `LuaValue#rawset` |
| `luaH_set` | `LuaTable#setEntry` |
| `luaH_setint` | `LuaTable#setInt` |
| `luaH_setmetatable` | `LuaTable#setmetatable` |
| `mainpositionTV` | `LuaTable#mainposition` |
| `numusearray` | `LuaTable` |
| `numusehash` | `LuaTable` |
| `psetint` | `LuaTable#psetLong` |
| `reinserthash` | `LuaTable` |
| `setdeadkey` | `LuaTable#setdeadkey` |
| `setnodevector` | `LuaTable` |
| `sizehash` | `LuaTable#hashBytes` |
| `sizenode` | `LuaTable#sizenode` |

### ltable.h

| C 函数 | Java 位置 |
|---|---|
| `farr2val` | `LuaTable#farr2val` |
| `fval2arr` | `LuaTable` |
| `fval2arr` | `LuaTable#fval2arr` |
| `luaH_fastgeti` | `LuaTable#fastGeti` |
| `luaH_fastseti` | `LuaTable#fastSeti` |
| `luaH_pset` | `LuaTable#HOK` |
| `luaV_fastget` | `LuaVM#table` |

### ltablib.c

| C 函数 | Java 位置 |
|---|---|
| `aux_getn` | `TableLib#auxGetN` |
| `auxsort` | `TableLib#auxsort` |
| `checktab` | `TableLib#checktab` |
| `hasField` | `TableLib#hasField` |
| `indexMetamethod` | `TableLib#indexMetamethod` |
| `luaL_Buffer` | `TableLib#LuaBuffer` |
| `luaL_pushresult` | `TableLib#result` |
| `luaV_fastgeti` | `TableLib#geti` |
| `luaV_fastseti` | `TableLib#seti` |
| `luaopen_table` | `TableLib#call` |
| `newindexMetamethod` | `TableLib#newindexMetamethod` |
| `partition` | `TableLib#partition` |
| `sortcomp` | `TableLib#sortComp` |
| `tconcat` | `TableLib#ConcatFn` |
| `tcreate` | `TableLib#CreateFn` |
| `tinsert` | `TableLib#InsertFn` |
| `tinsert` | `LuaTable#insert` |
| `tmove` | `TableLib#MoveFn` |
| `tpack` | `TableLib#PackFn` |
| `tremove` | `TableLib#RemoveFn` |
| `tremove` | `LuaTable#remove` |
| `tsort` | `TableLib#SortFn` |
| `tunpack` | `TableLib` |
| `tunpack` | `TableLib#UnpackFn` |
| `tunpack` | `TableLib#auxGetN` |
| `tunpack` | `TableLib#top` |

### ltm.c

| C 函数 | Java 位置 |
|---|---|
| `buildhiddenargs` | `LuaVM#nextraargs` |
| `callbinTM` | `LuaConcat` |
| `callbinTM` | `LuaCompare#callMetamethod` |
| `callbinTM` | `LuaVM#callbinTM` |
| `callbinTM` | `LuaConcat#lookup` |
| `createvarargtab` | `LuaVM#LuaTable` |
| `createvarargtab` | `LuaVM#setEntry` |
| `getnumargs` | `LuaVM#getnumargs` |
| `luaT_adjustvarargs` | `LuaVM#adjustVarargs` |
| `luaT_callTMres` | `LuaIndex#callOnStack2to1` |
| `luaT_callTMres` | `LuaVM#callTMres` |
| `luaT_callTMres` | `LuaIndex#callTMres3` |
| `luaT_callorderTM` | `LuaVM#callOrderTM` |
| `luaT_gettm` | `Metamethod#getTm` |
| `luaT_gettmbyobj` | `Metamethod#getTmByObj` |
| `luaT_gettmbyobj` | `Metamethod#lookup` |
| `luaT_objtypename` | `LuaValue#objTypeName` |
| `luaT_trybinTM` | `LuaVM#tryBinTM` |
| `luaT_trybinassocTM` | `LuaVM#tryBinAssocTM` |
| `luaT_trybiniTM` | `LuaVM#tryBiniTM` |
| `luaT_tryconcatTM` | `LuaVM#callbinTM` |
| `luaT_tryconcatTM` | `LuaVM#savedStart` |
| `tmname` | `Metamethod#tag` |

### ltm.h

| C 函数 | Java 位置 |
|---|---|
| `fasttm` | `Metamethod#lookup` |
| `gfasttm` | `LuaTable` |
| `luaT_gettm` | `LuaTable` |
| `ttypename` | `LuaBoolean#typeName` |

### lua.c

| C 函数 | Java 位置 |
|---|---|
| `pmain` | `LuaStandard` |
| `pmain` | `LuaPlatform#compileToChunk` |

### lua.h

| C 函数 | Java 位置 |
|---|---|
| `LUA_MULTRET` | `LuaValue#LUA_MULTRET` |
| `lua_isinteger` | `LuaValue#isinteger` |
| `lua_isnumber` | `LuaValue#isnumber` |
| `lua_isstring` | `LuaValue#isstring` |

### lundump.c

| C 函数 | Java 位置 |
|---|---|
| `checkHeader` | `LuaChunk#checkHeader` |
| `error` | `LuaChunk#error` |
| `loadDebug` | `LuaChunk#loadInt` |
| `luaU_undump` | `LuaChunk` |
| `luaU_undump` | `LuaChunk#loadByte` |
| `luaU_undump` | `LuaChunk#undump` |
| `numerror` | `LuaChunk` |

### lutf8lib.c

| C 函数 | Java 位置 |
|---|---|
| `byteoffset` | `Utf8Lib#offset` |
| `charpattern` | `Utf8Lib#set` |
| `codepoint` | `Utf8Lib#_code` |
| `iscont` | `Utf8Lib#isContByte` |
| `iter_aux` | `Utf8Lib#iter_aux` |
| `iter_codes` | `Utf8Lib#codes` |
| `luaopen_utf8` | `Utf8Lib#call` |
| `lutf8_encode` | `Utf8Lib#encodeUTF8` |
| `str_utfchar` | `Utf8Lib#_char` |
| `u_posrelat` | `Utf8Lib#uPosrelat` |
| `utf8_decode` | `Utf8Support#utf8Decode` |
| `utflen` | `Utf8Lib#len` |

### lvm.c

| C 函数 | Java 位置 |
|---|---|
| `L` | `LuaVM#top` |
| `OP_GETFIELD` | `LuaVM#opGetfield` |
| `OP_GETI` | `LuaVM#opGeti` |
| `OP_GETTABLE` | `LuaVM#opGettable` |
| `OP_GETTABUP` | `LuaVM#opGettabup` |
| `OP_LEN` | `LuaVM` |
| `OP_NOT` | `LuaVM` |
| `OP_SETI` | `LuaVM#opSeti` |
| `OP_SETTABLE` | `LuaVM#opSettable` |
| `OP_SETUPVAL` | `LuaVM` |
| `OP_TEST` | `LuaVM` |
| `OP_TESTSET` | `LuaVM` |
| `OP_TFORPREP` | `LuaVM#newTbcUpval` |
| `OP_VARARG` | `LuaVM#opVararg` |
| `ProtectNT` | `LuaVM` |
| `ProtectNT` | `LuaVM#savedpc` |
| `copy2buff` | `LuaConcat` |
| `copy2buff` | `LuaVM#count` |
| `floatforloop` | `LuaVM#floatforloop` |
| `forprep` | `LuaVM` |
| `forprep` | `LuaVM#forNumber` |
| `forprep` | `LuaVM#forprep` |
| `l_intfitsf` | `LuaCompare#intFitsFloat` |
| `l_strcmp` | `LuaString#cmp` |
| `luaF_getlocalname` | `LuaVM#getLocalName` |
| `luaV_concat` | `LuaVM` |
| `luaV_concat` | `LuaConcat#concat` |
| `luaV_equalobj` | `LuaCompare` |
| `luaV_equalobj` | `LuaCompare#equalObj` |
| `luaV_equalobj` | `BaseLib#rawEqualNum` |
| `luaV_execute` | `LuaVM#idx` |
| `luaV_execute` | `LuaVM#opGetvarg` |
| `luaV_finishget` | `LuaIndex#finishGet` |
| `luaV_finishget` | `LuaIndex#finishGetFromVM` |
| `luaV_finishget` | `LuaIndex#finishGeti` |
| `luaV_finishget` | `LuaIndex#finishGetiFromVM` |
| `luaV_finishset` | `LuaIndex#finishSet` |
| `luaV_finishseti` | `LuaIndex#finishSeti` |
| `luaV_finishseti` | `LuaIndex#finishSetiInt` |
| `luaV_finishseti` | `LuaIndex#finishSetiLong` |
| `luaV_fitsN` | `LuaVM#fitsLong` |
| `luaV_flttointeger` | `CodeGen#F2Ieq` |
| `luaV_flttointeger` | `LuaFloat#checkIntegerValid` |
| `luaV_flttointeger` | `LuaCompare#floatToInt` |
| `luaV_idiv` | `LuaArith#idiv` |
| `luaV_lessequal` | `LuaCompare#lessEqual` |
| `luaV_lessthan` | `LuaCompare#lessThan` |
| `luaV_mod` | `LuaArith#mod` |
| `luaV_objlen` | `LuaValue#len` |
| `luaV_objlen` | `LuaVM#objlen` |
| `luaV_shiftl` | `LuaVM#NBITS` |
| `luaV_shiftl` | `LuaArith#shiftLeft` |
| `luaV_shiftl` | `LuaArith#shl` |
| `luaV_tointegerns` | `LuaArith#canToIntegerNS` |
| `luaV_tointegerns` | `LuaArith#null` |
| `luai_nummod` | `LuaArith#floatMod` |
| `op_arith` | `LuaArith#apply` |
| `op_arith` | `LuaVM#opArith` |
| `op_arithI` | `LuaArith#apply` |
| `op_arithI` | `LuaVM#opArithIAdd` |
| `op_bitwise` | `LuaVM#opBitwise` |
| `op_bitwiseK` | `LuaVM#opBitwiseK` |
| `op_eqK` | `LuaVM#opEqK` |
| `op_order` | `LuaVM` |
| `op_order` | `LuaVM#opOrderEQ` |
| `op_orderI` | `LuaVM#opOrderI` |
| `pushclosure` | `LuaVM#pushclosure` |
| `tointegerns` | `LuaVM#arithToLongOrNull` |
| `tointegerns` | `LuaArith#toIntegerNS` |
| `tonumberns` | `LuaArith#toNumberNS` |
| `tostring` | `LuaConcat#canConcatFast` |
| `ttisinteger` | `LuaVM` |
| `vmfetch` | `LuaVM` |

### lvm.h

| C 函数 | Java 位置 |
|---|---|
| `luaV_finishfastset` | `LuaIndex#barrierback` |

## 三、Java 独有层（无 C 对应）

`luajvm-core` 中带 `// java-only:` 文件头的类（本脚本只扫 core 源码树）：

| Java 类 | 说明 |
|---|---|
| `bind.Coercion` | Lua/Java 类型强制转换 |
| `bind.InvokeSupport` | 反射调用统一入口（纯 Method.invoke / Field.get-set，不含 MethodHandle） |
| `bind.JavaBinding` | 纯 Java 实现的绑定包装标记 |
| `bind.JavaCall` | Java <-> Lua 双向调用的便捷封装（bind 层互操作） |
| `bind.JavaClass` | Java类反射绑定 |
| `bind.JavaCollection` | Lua 表式访问 Java 集合/数组/Map（无 C 对应） |
| `bind.JavaConstructor` | Java构造器反射绑定 |
| `bind.JavaLib` | Java库注册 |
| `bind.JavaMethod` | Java 方法反射绑定 |
| `bind.JavaObject` | Java对象Lua包装 |
| `bind.MemberSupport` | 成员访问辅助 |
| `bind.Platform` | luajava 宿主绑定的标准 Globals 工厂（兼容入口，保持公开 API 不变） |
| `core.ContSupport` | jdk.internal.vm.Continuation 的反射适配层（协程第三模式的底座）。 |
| `core.LuaInteger` | v 保持 final —— JVM 标量替换/TLAB 使短命 LuaInteger 近乎免费 |
| `core.LuaStates` | 活动 Globals 的中立登记表。 |
| `core.Varargs` | 多返回值容器，C用栈传递多返回值 |
| `spi.BaseLibrary` | 基础库 SPI —— 让 core/vm 无需反向 import lib.BaseLib |
| `spi.Compiler` | 编译器SPI接口 |
| `spi.CompilerHooks` | 编译器钩子SPI |
| `spi.DebugTracer` | 调用追踪 SPI —— 让 core/vm 无需反向 import lib.DebugLib |
| `spi.Loader` | 加载器SPI接口 |
| `spi.Logger` | 日志SPI接口 |
| `spi.Loggers` | 日志 SPI 的静态持有者 |
| `spi.LuaConfig` | Lua配置SPI |
| `spi.LuaJavaContext` | Lua/Java上下文 |
| `tools.LuacCompiler` | 批量预编译工具（luac.c 的等价物，供构建期把 .lua 编成 .luac）。 |
| `vm.CFnCallStats` | C 函数 callOnStack 命中率探针（-Dluajvm.countcfn=true） |
| `vm.FlatArith` | 扁平算术核（免装箱） - long/double 寄存器直算，对齐 LuaVM.op_arith 快路径 |
| `vm.FlatIFor` | 数值 for（OP_FORPREP/FORLOOP）整循环扁平化执行器（无 C 对应）。 |
| `vm.FlatTFor` | TFOR（泛型 for pairs/ipairs）循环扁平化执行器（无 C 对应）。 |

- `luajvm-android` 模块整体为平台绑定层（AGENTS.md 规定无文件头要求），Lua 可见面契约见 AGENTS.md「改名硬约束」。
- 门禁任务与判据见 docs/GATES.md。

## 四、再生成

```bash
python scripts/gen-mapping.py   # 重新扫描并覆盖 docs/mapping.md
```
