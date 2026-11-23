package dev.amenhancer.plugin.api;

/** 编译期桩：真实类型是 final class，不要继承。 */
public final class PluginUnsupportedException extends Exception {
    public PluginUnsupportedException(String message) {
        super(message);
    }
}
