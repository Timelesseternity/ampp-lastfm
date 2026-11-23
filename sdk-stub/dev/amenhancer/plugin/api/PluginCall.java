package dev.amenhancer.plugin.api;

import java.lang.reflect.Executable;

/** 编译期桩：真实类型是 final class。 */
public final class PluginCall {
    public Executable method;
    public Object receiver;
    public Object[] arguments;
    public Object result;
    public Throwable throwable;
    public boolean outcomeChanged;

    public PluginCall(Executable method, Object receiver, Object[] arguments, Object result, Throwable throwable) {
        this.method = method;
        this.receiver = receiver;
        this.arguments = arguments;
        this.result = result;
        this.throwable = throwable;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public Executable getMethod() {
        return method;
    }

    public Object getReceiver() {
        return receiver;
    }

    public Object getResult() {
        return result;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public boolean isOutcomeChanged() {
        return outcomeChanged;
    }

    public void returnResult(Object value) {
        this.result = value;
        this.outcomeChanged = true;
    }

    public void throwException(Throwable error) {
        this.throwable = error;
        this.outcomeChanged = true;
    }
}
