// ref: lcorolib.c
// diff: 内部类代替C闭包; LuaThread代替lua_State线程; Globals传递全局状态; auxstatus返回COS_*常量; CoWrapperFn对应luaB_auxwrap
package org.luajvm.lib;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaThread;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

public class CoroutineLib extends LuaFunction {

    // lcorolib.c: statname
    private static final String[] statname = {"running", "dead", "suspended", "normal"};
    // COS_*
    private static final int COS_RUN = 0;
    private static final int COS_DEAD = 1;
    private static final int COS_YIELD = 2;
    private static final int COS_NORM = 3;

    // lcorolib.c: auxstatusStr
    static String auxstatusStr(Globals g, LuaThread co) {
        return co.auxstatus();
    }

    // lcorolib.c: auxstatus
    static int auxstatus(Globals g, LuaThread co) {
        LuaThread L = g.running;
        if (L == co) return COS_RUN;
        if (co.isNormal) return COS_NORM;
        switch (co.status) {
            case LuaThread.LUA_YIELD:
                return COS_YIELD;
            case LuaThread.LUA_OK: {

                if (co.ci != null && co.ci != co.base_ci) {
                    return COS_NORM;
                }
                if (co.getFunc() == null) {
                    return COS_DEAD;
                }

                return COS_YIELD;
            }
            default:

                return COS_DEAD;
        }
    }

    // lcorolib.c: luaopen_coroutine
    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        Globals g = env.checkglobals();
        LuaTable co = new LuaTable();
        co.set("create", new CoCreateFn(g));
        co.set("resume", new CoResumeFn(g));
        co.set("running", new CoRunningFn(g));
        co.set("status", new CoStatusFn(g));
        co.set("yield", new CoYieldFn(g));
        co.set("wrap", new CoWrapFn(g));
        co.set("isyieldable", new CoIsYieldableFn(g));
        co.set("close", new CoCloseFn(g));
        env.set("coroutine", co);
        if (!env.get("package").isnil())
            env.get("package").get("loaded").set("coroutine", co);
        return co;
    }

    // CoCreateFn
    static class CoCreateFn extends LuaFunction {
        final Globals g;

        CoCreateFn(Globals g) {
            this.g = g;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            LuaFunction f = arg.checkfunction();
            LuaThread co = new LuaThread(g, f);
            DebugHook.inheritHooks(g, g.running, co);  // lstate.c: lua_newthread  -  新线程继承创建线程的 hook
            return co;
        }
    }

    // CoResumeFn
    // lcorolib.c: auxresume + luaB_coresume
    static class CoResumeFn extends LuaFunction {
        final Globals g;

        CoResumeFn(Globals g) {
            this.g = g;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaThread co = args.checkthread(1);
            // java diff: C 用 lua_xmove 直接拷栈，零分配；Java 需打包为 Varargs。
            // 快路径避免 subargs(2) 的数组分配：0 额外参数走 NONE 单例，1 个走 args.arg(2)
            // （本身即 Varargs，零分配），2+ 才走 subargs(2) 数组路径。
            int n = args.narg();
            Varargs resumeArgs;
            if (n <= 1) resumeArgs = LuaValue.NONE;
            else if (n == 2) resumeArgs = args.arg(2);
            else resumeArgs = args.subargs(2);
            Varargs result = co.lua_resume(resumeArgs);
            // lcorolib.c: auxresume  -  lua_checkstack(L, nres + 1)
            // java diff: C 在 lua_resume 返回后检查主线程栈空间；
            // Java 必须在 precallC 尝试把结果复制到主线程栈前做同样检查
            if (result.arg1().toboolean()) {
                int nres = result.narg() - 1;  // exclude the boolean
                LuaThread L = g != null ? g.running : null;
                if (L != null && L.top + nres + 1 > 1000000) {
                    return LuaValue.varargsOf(LuaValue.FALSE, LuaString.newStr("too many results to resume"));
                }
            }
            return result;
        }
    }

    // CoRunningFn
    static class CoRunningFn extends LuaFunction {
        final Globals g;

        CoRunningFn(Globals g) {
            this.g = g;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaThread r = g.running;
            return varargsOf(r, valueOf(r.isMainThread()));
        }
    }

    // CoStatusFn
    static class CoStatusFn extends LuaFunction {
        final Globals g;

        CoStatusFn(Globals g) {
            this.g = g;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaThread co = args.checkthread(1);

            return valueOf(statname[auxstatus(g, co)]);
        }
    }

    // CoYieldFn
    static class CoYieldFn extends LuaFunction {
        final Globals g;

        CoYieldFn(Globals g) {
            this.g = g;
        }

        @Override
        public Varargs call(Varargs args) {
            return g.yield(args);
        }
    }

    // CoWrapFn
    static class CoWrapFn extends LuaFunction {
        final Globals g;

        CoWrapFn(Globals g) {
            this.g = g;
        }

        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            LuaThread co = new LuaThread(g, arg.checkfunction());
            DebugHook.inheritHooks(g, g.running, co);  // lstate.c: lua_newthread  -  新线程继承创建线程的 hook
            return new CoWrapperFn(g, co);
        }
    }

    // CoIsYieldableFn
    static class CoIsYieldableFn extends LuaFunction {
        final Globals g;

        CoIsYieldableFn(Globals g) {
            this.g = g;
        }

        @Override
        public Varargs call(Varargs args) {

            LuaValue arg1 = args.arg(1);
            LuaThread t = arg1.isnil() ? (g != null ? g.running : null) : arg1.checkthread();
            if (t == null) return LuaValue.FALSE;
            return LuaValue.valueOf(!t.isMainThread() && t.nny == 0);
        }
    }

    // CoCloseFn
    static class CoCloseFn extends LuaFunction {
        final Globals g;

        CoCloseFn(Globals g) {
            this.g = g;
        }

        @Override
        public Varargs call(Varargs args) {

            LuaThread t = args.narg() < 1 ? (g != null ? g.running : null) : args.checkthread(1);
            if (t == null) LuaErrors.argError(1, "thread expected");

            int cos = auxstatus(g, t);

            switch (cos) {
                case COS_DEAD:
                case COS_YIELD: {
                    return t.close();
                }
                case COS_NORM:

                    return LuaErrors.error("cannot close a normal coroutine");
                case COS_RUN: {

                    if (t.isMainThread()) {
                        return LuaErrors.error("cannot close main thread");
                    }

                    t.lua_closethread();

                    return LuaValue.NONE;
                }
                default:
                    return LuaValue.NONE;
            }
        }
    }

    // lcorolib.c: luaB_auxwrap
    static class CoWrapperFn extends LuaFunction {
        final LuaThread thread;
        // java-only: gcRefs() 每个 GC 周期都被调用（登记后每周期复位颜色->重新遍历），
        //   返回值恒定，故建对象时一次成型，避免每周期新建数组。
        private final LuaValue[] gcRefs;

        CoWrapperFn(Globals g, LuaThread t) {
            this.thread = t;
            this.gcRefs = new LuaValue[]{t};
            // lcorolib.c: luaB_cowrap 用 lua_pushcclosure(L, luaB_auxwrap, 1)  -  C 闭包本身是
            //   allgc 上的 GC 对象，每周期被 sweep 重新染白，上值（协程）每周期都被重新标记。
            // java diff: LuaFunction 只有经 bindGlobals 登记进 allFunctions 才会被重新染白；
            //   不登记则首次 GC 标黑后 gcRefs() 再不被遍历，被持有的协程漏标回收 ->
            //   "cannot resume dead coroutine"，故必须 bindGlobals。
            bindGlobals(g);
        }

        @Override
        public LuaValue[] gcRefs() {
            return gcRefs;
        }

        @Override
        public Varargs call(Varargs args) {
            Varargs r = thread.lua_resume(args);
            if (r.arg1().toboolean()) return r.subargs(2);
            // lcorolib.c: luaB_auxwrap  -  协程中的 luaL_error：关闭 TBC 变量
            LuaValue err = r.arg(2);
            int stat = thread.status;
            if (stat != LuaThread.LUA_OK && stat != LuaThread.LUA_YIELD) {
                // lcorolib.c: stat = lua_closethread(co, L);
                Varargs closeResult = thread.close();
                if (!closeResult.arg1().toboolean()) {
                    err = closeResult.arg(2);
                }
            }
            // lcorolib.c: luaB_auxwrap  -  字符串 luaL_error 且非内存 luaL_error 时附加额外信息
            if (err instanceof LuaString) {
                // lcorolib.c: stat != LUA_ERRMEM  -  内存错误不加前缀
                // java diff: C用stat!=LUA_ERRMEM判断，Java用字符串比较（无ERRMEM状态码）
                if ("not enough memory".equals(err.toJavaString()))
                    throw LuaErrors.errorObject(err, 0);
                // lcorolib.c: luaL_where(L,1) + lua_insert + lua_concat
                try {
                    LuaString where = thread.l_G != null && thread.l_G.baselib != null
                            ? thread.l_G.baselib.where(1) : LuaString.newStr("");
                    if (where != null && where.length() > 0) {
                        err = LuaString.newStr(where.toJavaString() + err.toJavaString());
                    }
                } catch (StackOverflowError ignored) {
                    // java diff: 深度递归时luaL_where可能StackOverflow，C中不存在此问题
                }
                throw LuaErrors.errorObject(err, 0);
            }
            throw LuaErrors.errorObject(err, 0);
        }
    }


}
