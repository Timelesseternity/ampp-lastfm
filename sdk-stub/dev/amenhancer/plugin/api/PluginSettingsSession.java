package dev.amenhancer.plugin.api;

import android.view.View;

/** 编译期桩类，真实实现由宿主提供。 */
public interface PluginSettingsSession {
    View getView();

    void close();
}
