package dev.logast.model;

import java.util.ArrayList;
import java.util.List;

public final class MethodFacts {
    public final List<LogCall> logs = new ArrayList<>();
    /** Log calls through another logging API: no rule checks them, the coverage report lists them as skipped (#72). */
    public final List<OtherLogCall> otherLogs = new ArrayList<>();
    public final List<MdcOp> mdc = new ArrayList<>();
    public final List<CatchInfo> catches = new ArrayList<>();
    public final List<CallSite> calls = new ArrayList<>();
    public final List<AsyncHop> asyncHops = new ArrayList<>();
    public final List<String> unresolved = new ArrayList<>();
    /** Log calls recognised by name only (the logger's type did not resolve, e.g. Lombok missing from the classpath). */
    public int loggersByName;
}
