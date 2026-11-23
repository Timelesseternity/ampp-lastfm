package dev.amenhancer.plugin.api;

import java.lang.reflect.Executable;

/** 编译期桩：真实类型是 interface。 */
public interface PluginHooks {
    PluginRegistration hook(Executable target, boolean exclusive, PluginHook callback);

    PluginRegistration observe(Executable target, PluginObserver callback);
}
