package com.zwx.zwxagent.capability.tools;

/** Identifies the worker on the thread that invokes a tool callback. */
public final class ToolCallWorkerContext {
    private static final ThreadLocal<String> WORKER = new ThreadLocal<>();

    private ToolCallWorkerContext() { }

    public static void set(String workerKey) { WORKER.set(workerKey); }

    public static String current() { return WORKER.get(); }

    public static void clear() { WORKER.remove(); }
}
