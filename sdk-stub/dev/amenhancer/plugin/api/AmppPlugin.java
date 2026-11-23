package dev.amenhancer.plugin.api;

import android.content.Context;

/**
 * 编译期桩类。真实实现由 AM++ 宿主在运行时提供。
 * 签名依据 AM++ v1.7.1 (plugin-api v1) 的 dex 导出，勿改动。
 */
public abstract class AmppPlugin {
    public AmppPlugin() {
    }

    public void onLoad(PluginContext context) throws Exception {
        throw new UnsupportedOperationException("stub");
    }

    public void onStart() {
        throw new UnsupportedOperationException("stub");
    }

    public void onStop() {
        throw new UnsupportedOperationException("stub");
    }

    public PluginSettingsSession createSettings(Context context) {
        throw new UnsupportedOperationException("stub");
    }
}
