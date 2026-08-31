package org.luajvm.rewriter;

/**
 * 单条重写规则。
 *
 * @param descContains 可选的方法描述符子串：同名重载区分用（如 toString 的 Charset 变体），
 *                     null 表示任意描述符
 */
public record Rewrite(String owner, String name, String compat, String recv, boolean staticCall, String descContains) {
    Rewrite(String owner, String name, String compat, String recv) {
        this(owner, name, compat, recv, false, null);
    }

    Rewrite(String owner, String name, String compat, String recv, boolean staticCall) {
        this(owner, name, compat, recv, staticCall, null);
    }

    boolean matches(String owner, String name, String desc) {
        if (!this.owner.equals(owner) || !this.name.equals(name)) return false;
        return descContains == null || desc.contains(descContains);
    }
}
