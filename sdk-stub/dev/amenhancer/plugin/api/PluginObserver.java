package dev.amenhancer.plugin.api;

/** 编译期桩类，真实实现由宿主提供。 */
public interface PluginObserver {
    void before(PluginObservation call);

    void after(PluginObservation call);
}
