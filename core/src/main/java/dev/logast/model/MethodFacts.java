package dev.logast.model;

import java.util.ArrayList;
import java.util.List;

public final class MethodFacts {
    public final List<LogCall> logs = new ArrayList<>();
    public final List<MdcOp> mdc = new ArrayList<>();
    public final List<CatchInfo> catches = new ArrayList<>();
    public final List<CallSite> calls = new ArrayList<>();
    public final List<AsyncHop> asyncHops = new ArrayList<>();
    public final List<String> unresolved = new ArrayList<>();
}
