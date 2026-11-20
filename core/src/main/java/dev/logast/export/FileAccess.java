package dev.logast.export;

import dev.logast.graph.CodeIndex;
import dev.logast.graph.Constants;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #172: which code writes and reads which files: Azure Blob containers and blobs, SFTP paths (JSch, sshj, Spring
 * Integration). The location comes from constants, {@code @Value} fields (the property's value in the base config,
 * else {@code ${key}}) and partly constant concatenations ({@code "/outbound/" + date} → {@code /outbound/{?}}),
 * following a receiver back through local variables and fields.
 */
final class FileAccess {
    record Access(MethodInfo method, boolean write, String location, String via, String file, int line) {
    }

    private static final String BLOB = "com.azure.storage.blob.specialized.BlobClientBase";
    private static final String CONTAINER = "com.azure.storage.blob.BlobContainerClient";
    private static final String SERVICE = "com.azure.storage.blob.BlobServiceClient";
    private static final String CONTAINER_BUILDER = "com.azure.storage.blob.BlobContainerClientBuilder";
    private static final Pattern BLOB_WRITE = Pattern.compile("^(upload|stage|commit|delete|setMetadata|setTags|beginCopy|copyFromUrl).*");
    private static final Pattern BLOB_READ = Pattern.compile("^(download|openInputStream|exists|getProperties|getTags|query).*");
    /** SFTP clients: type → method → (write?, index of the remote path argument). */
    private static final Map<String, Map<String, int[]>> SFTP = Map.of(
            "com.jcraft.jsch.ChannelSftp", Map.of("put", new int[]{1, 1}, "get", new int[]{0, 0}, "rm", new int[]{1, 0},
                    "ls", new int[]{0, 0}, "rename", new int[]{1, 1}, "mkdir", new int[]{1, 0}),
            "net.schmizz.sshj.sftp.SFTPClient", Map.of("put", new int[]{1, 1}, "get", new int[]{0, 0}, "rm", new int[]{1, 0},
                    "ls", new int[]{0, 0}, "rename", new int[]{1, 1}),
            "org.springframework.integration.file.remote.session.Session", Map.of("write", new int[]{1, 1}, "append", new int[]{1, 1},
                    "remove", new int[]{1, 0}, "read", new int[]{0, 0}, "list", new int[]{0, 0}, "readRaw", new int[]{0, 0},
                    "rename", new int[]{1, 1}),
            "org.springframework.integration.file.remote.RemoteFileTemplate", Map.of("get", new int[]{0, 0}, "list", new int[]{0, 0},
                    "remove", new int[]{1, 0}, "exists", new int[]{0, 0}, "rename", new int[]{1, 1}));
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");

    private final CodeIndex index;
    private final Map<String, String> config;

    FileAccess(CodeIndex index, Map<String, String> config) {
        this.index = index;
        this.config = config;
    }

    List<Access> accesses() {
        List<Access> out = new ArrayList<>();
        for (MethodInfo m : index.methods()) {
            if (!m.hasBody()) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    J.MethodInvocation v = super.visitMethodInvocation(mi, p);
                    access(m, mi, out);
                    return v;
                }

                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, Integer p) {
                    return cd;
                }
            }.visit(m.decl().getBody(), 0);
        }
        return out;
    }

    private void access(MethodInfo m, J.MethodInvocation mi, List<Access> out) {
        JavaType.Method mt = mi.getMethodType();
        if (mt == null) {
            return;
        }
        JavaType.FullyQualified decl = mt.getDeclaringType();
        String name = mi.getSimpleName();
        String file = m.file().relPath();
        int line = Pos.line(mi.getName());
        if (TypeUtils.isAssignableTo(BLOB, decl) || TypeUtils.isOfClassType(decl, BLOB)) {
            boolean write = BLOB_WRITE.matcher(name).matches();
            if (write || BLOB_READ.matcher(name).matches()) {
                out.add(new Access(m, write, "blob:" + blobLocation(m, mi.getSelect(), 0), simple(decl) + "." + name, file, line));
            }
        } else if ((TypeUtils.isAssignableTo(CONTAINER, decl) || TypeUtils.isOfClassType(decl, CONTAINER))
                && name.startsWith("listBlobs")) {
            out.add(new Access(m, false, "blob:" + container(m, mi.getSelect(), 0) + "/{?}", simple(decl) + "." + name, file, line));
        } else {
            for (var e : SFTP.entrySet()) {
                if (TypeUtils.isAssignableTo(e.getKey(), decl) || TypeUtils.isOfClassType(decl, e.getKey())) {
                    int[] op = e.getValue().get(name);
                    List<Expression> args = mi.getArguments();
                    if (op != null && args.size() > op[1] && !(args.get(op[1]) instanceof J.Empty)) {
                        out.add(new Access(m, op[0] == 1, "sftp:" + text(m, args.get(op[1]), 0), simple(decl) + "." + name, file, line));
                    }
                }
            }
        }
    }

    /** {@code <container>/<blob>} for the receiver of a blob operation: {@code getBlobClient(b)} on a container client. */
    private String blobLocation(MethodInfo m, Expression receiver, int depth) {
        Expression r = origin(m, receiver, depth);
        if (r instanceof J.MethodInvocation gi && gi.getSimpleName().matches("get(Blob|BlockBlob|AppendBlob|PageBlob)Client|getBlockBlobClient")) {
            if (gi.getSimpleName().equals("getBlockBlobClient") && gi.getSelect() != null) {   // blobClient.getBlockBlobClient()
                return blobLocation(m, gi.getSelect(), depth + 1);
            }
            String blob = gi.getArguments().isEmpty() || gi.getArguments().getFirst() instanceof J.Empty ? "{?}" : text(m, gi.getArguments().getFirst(), depth + 1);
            return container(m, gi.getSelect(), depth + 1) + "/" + blob;
        }
        return "{?}/{?}";
    }

    /** The container name a container client was made for. */
    private String container(MethodInfo m, Expression receiver, int depth) {
        Expression r = origin(m, receiver, depth);
        if (r instanceof J.MethodInvocation ci) {
            if (ci.getSimpleName().equals("getBlobContainerClient") && !ci.getArguments().isEmpty()) {
                return text(m, ci.getArguments().getFirst(), depth + 1);
            }
            if (ci.getSimpleName().equals("buildClient") || ci.getSimpleName().equals("buildAsyncClient")) {
                for (Expression x = ci.getSelect(); x instanceof J.MethodInvocation b; x = b.getSelect()) {
                    if (b.getSimpleName().equals("containerName") && !b.getArguments().isEmpty()) {
                        return text(m, b.getArguments().getFirst(), depth + 1);
                    }
                }
            }
        }
        return "{?}";
    }

    /** Follows an identifier back to what it was assigned: a local variable's or a field's initializer. */
    private Expression origin(MethodInfo m, Expression e, int depth) {
        if (depth > 6 || !(e instanceof J.Identifier id)) {
            return e;
        }
        Expression init = initializer(m.decl(), id.getSimpleName());
        if (init == null) {
            init = fieldInitializer(m.owner(), id.getSimpleName());
        }
        return init == null ? e : origin(m, init, depth + 1);
    }

    /** A location as text: constants, @Value fields, and {@code {?}} for what the code computes. */
    private String text(MethodInfo m, Expression e, int depth) {
        String c = Constants.concatenated(index, e);
        if (c != null) {
            return resolve(c);
        }
        if (e instanceof J.Binary b && b.getOperator() == J.Binary.Type.Addition) {
            return join(text(m, b.getLeft(), depth + 1), text(m, b.getRight(), depth + 1));
        }
        if (e instanceof J.Identifier id && depth < 6) {
            String v = valueField(m.owner(), id.getSimpleName());
            if (v != null) {
                return resolve(v);
            }
            Expression init = initializer(m.decl(), id.getSimpleName());
            if (init != null) {
                return text(m, init, depth + 1);
            }
        }
        if (e instanceof J.MethodInvocation mi && mi.getSimpleName().equals("resolve") && mi.getSelect() != null && depth < 6) {
            return join(text(m, mi.getSelect(), depth + 1) + "/", mi.getArguments().isEmpty() ? "{?}" : text(m, mi.getArguments().getFirst(), depth + 1));
        }
        return "{?}";
    }

    private static String join(String a, String b) {
        String s = a + b;
        return s.replace("{?}{?}", "{?}").replace("//", "/");
    }

    /** {@code ${key:default}} → the base config's value, else the default, else {@code ${key}}. */
    private String resolve(String s) {
        Matcher m = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = config.get(m.group(1).strip());
            if (v == null) {
                v = m.group(2) != null ? m.group(2) : "${" + m.group(1).strip() + "}";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The {@code @Value("…")} of a field (or constructor parameter that assigns it), as written. */
    private static String valueField(ClassInfo c, String name) {
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(name))) {
                for (J.Annotation a : vd.getLeadingAnnotations()) {
                    if (Annotations.is(a, "org.springframework.beans.factory.annotation.Value")) {
                        return Annotations.rawAttribute(a, "value").map(FileAccess::unquote).orElse(null);
                    }
                }
            }
            if (s instanceof J.MethodDeclaration md && md.isConstructor() && md.getBody() != null) {
                for (Statement p : md.getParameters()) {
                    if (p instanceof J.VariableDeclarations pd && (pd.getVariables().getFirst().getSimpleName().equals(name)
                            || assigns(md, name, pd.getVariables().getFirst().getSimpleName()))) {
                        for (J.Annotation a : pd.getLeadingAnnotations()) {
                            if (Annotations.is(a, "org.springframework.beans.factory.annotation.Value")) {
                                return Annotations.rawAttribute(a, "value").map(FileAccess::unquote).orElse(null);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean assigns(J.MethodDeclaration ctor, String field, String param) {
        for (Statement s : ctor.getBody().getStatements()) {
            if (s instanceof J.Assignment as && as.getAssignment() instanceof J.Identifier v && v.getSimpleName().equals(param)) {
                String target = as.getVariable() instanceof J.FieldAccess fa ? fa.getSimpleName()
                        : as.getVariable() instanceof J.Identifier i ? i.getSimpleName() : null;
                if (field.equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Expression initializer(J.MethodDeclaration md, String name) {
        Expression[] found = {null};
        if (md.getBody() == null) {
            return null;
        }
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                if (v.getSimpleName().equals(name) && v.getInitializer() != null && found[0] == null) {
                    found[0] = v.getInitializer();
                }
                return v;
            }
        }.visit(md.getBody(), 0);
        return found[0];
    }

    /** A field's initializer, or what the constructor assigns to it. */
    private static Expression fieldInitializer(ClassInfo c, String name) {
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd) {
                for (var v : vd.getVariables()) {
                    if (v.getSimpleName().equals(name) && v.getInitializer() != null) {
                        return v.getInitializer();
                    }
                }
            }
            if (s instanceof J.MethodDeclaration md && md.isConstructor() && md.getBody() != null) {
                for (Statement st : md.getBody().getStatements()) {
                    if (st instanceof J.Assignment as) {
                        String target = as.getVariable() instanceof J.FieldAccess fa ? fa.getSimpleName()
                                : as.getVariable() instanceof J.Identifier i ? i.getSimpleName() : null;
                        if (name.equals(target)) {
                            return as.getAssignment();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String simple(JavaType.FullyQualified t) {
        return t.getClassName().substring(t.getClassName().lastIndexOf('.') + 1);
    }

    private static String unquote(String raw) {
        String s = raw.strip();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    static final Set<String> STORES = Set.of("blob", "sftp");
}
