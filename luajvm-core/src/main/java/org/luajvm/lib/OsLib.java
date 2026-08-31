// ref: loslib.c
// diff: Calendar代替struct tm; nanoTime()代替clock(); Runtime.exec()代替system(); 计数器代替tmpnam(); Locale代替setlocale(collate/ctype不支持); getenv回退到getProperty; remove/rename关联关闭文件句柄
package org.luajvm.lib;

import org.luajvm.core.Globals;
import org.luajvm.core.LuaError;
import org.luajvm.core.LuaErrors;
import org.luajvm.core.LuaFloat;
import org.luajvm.core.LuaFunction;
import org.luajvm.core.LuaInteger;
import org.luajvm.core.LuaString;
import org.luajvm.core.LuaTable;
import org.luajvm.core.LuaValue;
import org.luajvm.core.Varargs;

import java.io.File;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.TimeZone;

public class OsLib extends LuaFunction {
    private static final long t0 = System.nanoTime();
    private static final String[] WEEKDAY_SHORT = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] WEEKDAY_FULL = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final String[] MONTH_SHORT = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private static final String[] MONTH_FULL = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    // java diff: C 用 tmpnam()；Java 用 nanoTime 为初始值的原子计数器，保证多线程唯一性
    private static final AtomicLong tmpCounter = new AtomicLong(t0);
    private static String currentLocaleAll = "C";
    private static String currentLocaleCollate = "C";
    private static String currentLocaleCtype = "C";
    private static String currentLocaleMonetary = "C";
    private static String currentLocaleNumeric = "C";
    private static String currentLocaleTime = "C";
    protected Globals globals;

    public OsLib() {
    }

    // lauxlib.c: luaL_execresult
    static Varargs execResult(int status) {

        LuaValue first = status == 0 ? LuaValue.TRUE : LuaValue.NIL;
        return LuaValue.varargsOf(first, LuaString.newStr("exit"), LuaInteger.valueOf(status));
    }

    private static String getCurrentLocale(String cat) {
        return switch (cat) {
            case "collate" -> currentLocaleCollate;
            case "ctype" -> currentLocaleCtype;
            case "monetary" -> currentLocaleMonetary;
            case "numeric" -> currentLocaleNumeric;
            case "time" -> currentLocaleTime;
            default -> currentLocaleAll;
        };
    }

    private static void setCurrentLocale(String cat, String loc) {
        switch (cat) {
            case "collate" -> currentLocaleCollate = loc;
            case "ctype" -> currentLocaleCtype = loc;
            case "monetary" -> currentLocaleMonetary = loc;
            case "numeric" -> currentLocaleNumeric = loc;
            case "time" -> currentLocaleTime = loc;
            default -> {
                currentLocaleAll = loc;
                currentLocaleCollate = loc;
                currentLocaleCtype = loc;
                currentLocaleMonetary = loc;
                currentLocaleNumeric = loc;
                currentLocaleTime = loc;
            }
        }
    }

    // loslib.c: getepochtime
    private static double currenttime() {
        return System.currentTimeMillis() / 1000L;
    }

    // loslib.c: getfield
    private static int getDateField(LuaTable t, String key, int defaultValue, int delta) {
        LuaValue v = t.get(key);
        long value;
        if (v.isnil()) {
            if (defaultValue < 0)
                throw LuaErrors.errorObject("field '" + key + "' missing in date table", 1);
            value = defaultValue;
        } else {
            try {
                value = v.checklong();
            } catch (LuaError e) {
                throw LuaErrors.errorObject("field '" + key + "' is not an integer", 1);
            }
        }
        long adjusted = value - delta;
        if (adjusted < Integer.MIN_VALUE || adjusted > Integer.MAX_VALUE) {
            throw LuaErrors.errorObject("field '" + key + "' is out-of-bound", 1);
        }
        return (int) adjusted;
    }

    // loslib.c: setallfields
    private static void setDateFields(LuaTable t, Calendar c) {
        t.set("year", LuaInteger.valueOf(c.get(Calendar.YEAR)));
        t.set("month", LuaInteger.valueOf(c.get(Calendar.MONTH) + 1));
        t.set("day", LuaInteger.valueOf(c.get(Calendar.DAY_OF_MONTH)));
        t.set("hour", LuaInteger.valueOf(c.get(Calendar.HOUR_OF_DAY)));
        t.set("min", LuaInteger.valueOf(c.get(Calendar.MINUTE)));
        t.set("sec", LuaInteger.valueOf(c.get(Calendar.SECOND)));
        t.set("wday", LuaInteger.valueOf((c.get(Calendar.DAY_OF_WEEK) - 1) % 7 + 1));
        t.set("yday", LuaInteger.valueOf(c.get(Calendar.DAY_OF_YEAR)));
        t.set("isdst", LuaValue.valueOf(c.getTimeZone().inDaylightTime(c.getTime())));
    }

    // loslib.c: strftime
    private static String formatDate(String fmt, Calendar d, double time) {
        StringBuilder sb = new StringBuilder();
        int n = fmt.length();
        for (int i = 0; i < n; ) {
            char c = fmt.charAt(i++);
            if (c == '\n') {
                sb.append('\n');
                continue;
            }
            if (c != '%') {
                sb.append(c);
                continue;
            }
            if (i >= n) LuaErrors.argError(1, "invalid conversion specifier '%'");
            c = fmt.charAt(i++);
            switch (c) {
                case '%' -> sb.append('%');
                case 'a' -> sb.append(WEEKDAY_SHORT[(d.get(Calendar.DAY_OF_WEEK) - 1) % 7]);
                case 'A' -> sb.append(WEEKDAY_FULL[(d.get(Calendar.DAY_OF_WEEK) - 1) % 7]);
                case 'b', 'h' -> sb.append(MONTH_SHORT[d.get(Calendar.MONTH)]);
                case 'B' -> sb.append(MONTH_FULL[d.get(Calendar.MONTH)]);
                case 'c' -> sb.append(formatDate("%a %b %d %H:%M:%S %Y", d, time));
                case 'd' ->
                        sb.append(String.format(Locale.US, "%02d", d.get(Calendar.DAY_OF_MONTH)));
                case 'e' ->
                        sb.append(String.format(Locale.US, "%2d", d.get(Calendar.DAY_OF_MONTH)));
                case 'H' ->
                        sb.append(String.format(Locale.US, "%02d", d.get(Calendar.HOUR_OF_DAY)));
                case 'I' -> {
                    int h = d.get(Calendar.HOUR);
                    if (h == 0) h = 12;
                    sb.append(String.format(Locale.US, "%02d", h));
                }
                case 'j' ->
                        sb.append(String.format(Locale.US, "%03d", d.get(Calendar.DAY_OF_YEAR)));
                case 'm' -> sb.append(String.format(Locale.US, "%02d", d.get(Calendar.MONTH) + 1));
                case 'M' -> sb.append(String.format(Locale.US, "%02d", d.get(Calendar.MINUTE)));
                case 'n' -> sb.append('\n');
                case 'p' -> sb.append(d.get(Calendar.HOUR_OF_DAY) < 12 ? "AM" : "PM");
                case 'S' -> sb.append(String.format(Locale.US, "%02d", d.get(Calendar.SECOND)));
                case 't' -> sb.append('\t');
                case 'w' -> sb.append((d.get(Calendar.DAY_OF_WEEK) - 1) % 7);
                case 'x' -> sb.append(formatDate("%m/%d/%y", d, time));
                case 'X' -> sb.append(formatDate("%H:%M:%S", d, time));
                case 'y' -> sb.append(String.format(Locale.US, "%02d", d.get(Calendar.YEAR) % 100));
                case 'Y' -> sb.append(d.get(Calendar.YEAR));
                case 'z' -> {
                    int off = d.getTimeZone().getOffset((long) (time * 1000)) / 60000;
                    sb.append(String.format(Locale.US, "%c%02d%02d", off >= 0 ? '+' : '-', Math.abs(off) / 60, Math.abs(off) % 60));
                }
                default -> LuaErrors.argError(1, "invalid conversion specifier '%" + c + "'");
            }
        }
        return sb.toString();
    }

    // loslib.c: luaopen_os
    @Override
    public Varargs call(Varargs args) {
        LuaValue modname = args.arg1();
        LuaValue env = args.arg(2);
        globals = env.checkglobals();
        LuaTable os = new LuaTable();
        os.set("clock", new os_clock());
        os.set("date", new os_date());
        os.set("difftime", new os_difftime());
        os.set("execute", new os_execute());
        os.set("exit", new os_exit());
        os.set("getenv", new os_getenv());
        os.set("remove", new os_remove());
        os.set("rename", new os_rename());
        os.set("setlocale", new os_setlocale());
        os.set("time", new os_time());
        os.set("tmpname", new os_tmpname());
        env.set("os", os);
        if (!env.get("package").isnil()) env.get("package").get("loaded").set("os", os);
        return os;
    }

    // loslib.c: os_clock
    static final class os_clock extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            return LuaFloat.valueOf((System.nanoTime() - t0) / 1e9);
        }
    }

    // loslib.c: os_date
    static final class os_date extends LuaFunction {
        public Varargs call(Varargs args) {
            String s = args.optJavaString(1, "%c");
            double t = args.isnumber(2) ? args.arg(2).todouble() : currenttime();
            boolean utc = s.startsWith("!");
            if (utc) s = s.substring(1);
            Calendar d = utc ? Calendar.getInstance(TimeZone.getTimeZone("UTC")) : Calendar.getInstance();
            d.setTime(new Date((long) (t * 1000)));
            if (s.equals("*t")) {
                LuaTable tbl = LuaValue.tableOf();
                tbl.set("year", LuaValue.valueOf(d.get(Calendar.YEAR)));
                tbl.set("month", LuaValue.valueOf(d.get(Calendar.MONTH) + 1));
                tbl.set("day", LuaValue.valueOf(d.get(Calendar.DAY_OF_MONTH)));
                tbl.set("hour", LuaValue.valueOf(d.get(Calendar.HOUR_OF_DAY)));
                tbl.set("min", LuaValue.valueOf(d.get(Calendar.MINUTE)));
                tbl.set("sec", LuaValue.valueOf(d.get(Calendar.SECOND)));
                tbl.set("wday", LuaValue.valueOf((d.get(Calendar.DAY_OF_WEEK) - 1) % 7 + 1));
                tbl.set("yday", LuaValue.valueOf(d.get(Calendar.DAY_OF_YEAR)));
                tbl.set("isdst", LuaValue.valueOf(d.getTimeZone().inDaylightTime(d.getTime())));
                return tbl;
            }
            return LuaString.newStr(formatDate(s, d, t));
        }
    }

    // loslib.c: os_difftime
    static final class os_difftime extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // loslib.c: luaL_checknumber 对两参都查（带 argerror 包装）
            double t2 = LuaErrors.checkDouble(args, 1);
            double t1 = LuaErrors.checkDouble(args, 2);
            return LuaFloat.valueOf(t2 - t1);
        }
    }

    // loslib.c: os_execute
    static final class os_execute extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg.isnil() || arg.isstring() && arg.toJavaString().isEmpty()) {
                try {
                    String shell = System.getProperty("os.name").toLowerCase().contains("win") ? "cmd /c" : "/bin/sh -c";
                    Runtime.getRuntime().exec(shell);
                    return LuaValue.TRUE;
                } catch (Exception e) {
                    return LuaValue.FALSE;
                }
            }
            try {
                String cmd = arg.checkJavaString();
                Process p = Runtime.getRuntime().exec(
                        System.getProperty("os.name").toLowerCase().contains("win")
                                ? new String[]{"cmd", "/c", cmd}
                                : new String[]{"/bin/sh", "-c", cmd});
                int status = p.waitFor();
                return execResult(status);
            } catch (Exception e) {
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr(e.getMessage()), LuaInteger.valueOf(1));
            }
        }
    }

    // loslib.c: os_exit
    static final class os_exit extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            int code = arg.optint(0);
            System.exit(code);
            return LuaValue.NONE;
        }
    }

    // loslib.c: os_getenv
    static final class os_getenv extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            String val = System.getenv(arg.checkJavaString());
            if (val == null) val = System.getProperty(arg.toJavaString());
            return val != null ? LuaString.newStr(val) : LuaValue.NIL;
        }
    }

    // loslib.c: os_remove
    static final class os_remove extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            File f = BaseLib.resolveFile(arg.checkJavaString());
            IoFile.closeHandlesForName(arg.checkJavaString());
            if (!f.exists())
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr(arg.checkJavaString() + ": no such file or directory"), LuaInteger.valueOf(2));
            if (!f.delete())
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr(arg.checkJavaString() + ": cannot delete"), LuaInteger.valueOf(2));
            return LuaValue.TRUE;
        }
    }

    // loslib.c: os_rename
    static final class os_rename extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue from = args.arg1();
            LuaValue to = args.arg(2);
            File f = BaseLib.resolveFile(from.checkJavaString());
            File t = BaseLib.resolveFile(to.checkJavaString());
            IoFile.closeHandlesForName(from.checkJavaString());
            if (!f.exists())
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr(from.checkJavaString() + ": no such file or directory"), LuaInteger.valueOf(2));
            if (!f.renameTo(t))
                return LuaValue.varargsOf(LuaValue.NIL, LuaString.newStr(from.checkJavaString() + ": cannot rename"), LuaInteger.valueOf(2));
            return LuaValue.TRUE;
        }
    }

    // loslib.c: os_setlocale
    static final class os_setlocale extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue locale = args.arg1();
            String cat = args.optJavaString(2, "all");

            if (locale.isnil()) return LuaString.newStr(getCurrentLocale(cat));
            String locStr = locale.checkJavaString();
            // java-only: 无原生strcoll，collate/ctype不支持
            if (cat.equals("collate") || cat.equals("ctype")) return LuaValue.NIL;

            try {
                Locale newLocale;
                if (locStr.equals("C") || locStr.equals("POSIX")) {
                    newLocale = Locale.ROOT;
                } else {
                    String[] parts = locStr.split("[._@]", 4);
                    Locale.Builder lb = new Locale.Builder();
                    lb.setLanguage(parts[0]);
                    if (parts.length > 1) lb.setRegion(parts[1]);
                    if (parts.length > 3) lb.setVariant(parts[3]);
                    newLocale = lb.build();
                }
                Locale.setDefault(newLocale);

                setCurrentLocale(cat, locStr);
                return LuaString.newStr(locStr);
            } catch (Exception e) {
                return LuaValue.NIL;
            }
        }
    }

    // loslib.c: os_time
    static final class os_time extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            LuaValue arg = args.arg1();
            if (arg.isnil()) return LuaInteger.valueOf((long) currenttime());
            LuaTable t = arg.checktable();
            Calendar c = Calendar.getInstance();
            c.setLenient(true);
            c.set(Calendar.YEAR, getDateField(t, "year", -1, 1900) + 1900);
            c.set(Calendar.MONTH, getDateField(t, "month", -1, 1));
            c.set(Calendar.DAY_OF_MONTH, getDateField(t, "day", -1, 0));
            c.set(Calendar.HOUR_OF_DAY, getDateField(t, "hour", 12, 0));
            c.set(Calendar.MINUTE, getDateField(t, "min", 0, 0));
            c.set(Calendar.SECOND, getDateField(t, "sec", 0, 0));
            c.set(Calendar.MILLISECOND, 0);
            long seconds = c.getTime().getTime() / 1000L;
            setDateFields(t, c);
            return LuaInteger.valueOf(seconds);
        }
    }

    // loslib.c: os_tmpname
    static final class os_tmpname extends LuaFunction {
        @Override
        public Varargs call(Varargs args) {
            // tmpCounter 是 AtomicLong，getAndIncrement 自身原子，无需外层锁
            return LuaString.newStr(".luaj" + tmpCounter.getAndIncrement() + ".tmp");
        }
    }
}
