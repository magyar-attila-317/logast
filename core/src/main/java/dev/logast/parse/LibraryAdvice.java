package dev.logast.parse;

import dev.logast.ShapeExport;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A {@code @ControllerAdvice} / {@code @RestControllerAdvice} class read from a class file on the classpath (#52): error
 * handling that lives in a shared library, which the sources do not show. Only libraries outside the public groups
 * ({@link ShapeExport#publicName}) are read: framework advice is scoped to the framework's own controllers, a company
 * library's advice usually applies to every controller of the service.
 *
 * @param className  the advice class
 * @param superName  its direct superclass ({@code ResponseEntityExceptionHandler} answers Spring MVC's exceptions)
 * @param handled    the exception types its {@code @ExceptionHandler} methods name (else their parameter types)
 * @param global     no selector (basePackages, assignableTypes, annotations, …) limits it to some controllers
 */
public record LibraryAdvice(String className, String superName, List<String> handled, boolean global) {
    private static final String ADVICE = "Lorg/springframework/web/bind/annotation/ControllerAdvice;";
    private static final String REST_ADVICE = "Lorg/springframework/web/bind/annotation/RestControllerAdvice;";
    private static final String HANDLER = "Lorg/springframework/web/bind/annotation/ExceptionHandler;";
    private static final byte[] MARK = "ControllerAdvice;".getBytes(StandardCharsets.US_ASCII);
    /** Per classpath entry (path, size, modification time): libraries do not change between the rounds of an apply. */
    private static final Map<String, List<LibraryAdvice>> CACHE = new ConcurrentHashMap<>();

    /** The advice classes of every jar and class folder on the classpath. */
    static List<LibraryAdvice> scan(List<Path> classpath) {
        List<LibraryAdvice> out = new ArrayList<>();
        for (Path p : classpath) {
            try {
                if (!Files.exists(p)) {
                    continue;
                }
                String key = p.toAbsolutePath() + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
                out.addAll(CACHE.computeIfAbsent(key, k -> read(p)));
            } catch (IOException ignored) {
                // unreadable entry: as if absent
            }
        }
        return out;
    }

    private static List<LibraryAdvice> read(Path p) {
        List<LibraryAdvice> out = new ArrayList<>();
        try {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.walk(p)) {
                    for (Path f : s.filter(f -> f.toString().endsWith(".class")).toList()) {
                        String name = p.relativize(f).toString().replace('\\', '/');
                        if (candidate(name)) {
                            try (InputStream in = Files.newInputStream(f)) {
                                parse(in.readAllBytes()).ifPresent(out::add);
                            }
                        }
                    }
                }
            } else if (p.toString().endsWith(".jar")) {
                try (ZipFile zip = new ZipFile(p.toFile())) {
                    for (ZipEntry e : Collections.list(zip.entries())) {
                        if (!e.isDirectory() && e.getName().endsWith(".class") && candidate(e.getName())) {
                            try (InputStream in = zip.getInputStream(e)) {
                                parse(in.readAllBytes()).ifPresent(out::add);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable or malformed: nothing from this entry
        }
        return List.copyOf(out);
    }

    /** A class outside the public groups (not module-info, not under META-INF/versions twice). */
    private static boolean candidate(String entry) {
        if (entry.startsWith("META-INF/") || entry.endsWith("module-info.class") || entry.endsWith("package-info.class")) {
            return false;
        }
        String fqn = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
        return !ShapeExport.publicName(fqn, List.of());
    }

    static Optional<LibraryAdvice> parse(byte[] bytes) throws IOException {
        if (indexOf(bytes, MARK) < 0) {
            return Optional.empty();   // most classes: no need to read the constant pool
        }
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            return Optional.empty();
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        Object[] pool = new Object[count];
        int[] classIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> pool[i] = in.readUTF();
                case 7 -> classIndex[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();   // access
        String self = className(pool, classIndex, in.readUnsignedShort());
        int superIdx = in.readUnsignedShort();
        String superName = superIdx == 0 ? null : className(pool, classIndex, superIdx);
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            in.readUnsignedShort();
        }
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            skipAttributes(in);
        }
        List<String> handled = new ArrayList<>();
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            String desc = (String) pool[in.readUnsignedShort()];
            for (Annotation a : annotations(in, pool)) {
                if (a.type().equals(HANDLER)) {
                    List<String> types = new ArrayList<>(a.classes("value"));
                    types.addAll(a.classes("exception"));
                    if (types.isEmpty()) {
                        types.addAll(parameterTypes(desc));
                    }
                    handled.addAll(types);
                }
            }
        }
        boolean advice = false;
        boolean global = true;
        for (Annotation a : annotations(in, pool)) {
            if (a.type().equals(ADVICE) || a.type().equals(REST_ADVICE)) {
                advice = true;
                global = a.elements().stream().noneMatch(e -> Set.of("value", "basePackages", "basePackageClasses",
                        "assignableTypes", "annotations").contains(e));
            }
        }
        return advice ? Optional.of(new LibraryAdvice(self, superName, List.copyOf(handled), global)) : Optional.empty();
    }

    private static String className(Object[] pool, int[] classIndex, int i) {
        return ((String) pool[classIndex[i]]).replace('/', '.');
    }

    /** Object parameter types of a method descriptor, java.lang.Object left out (it would match everything). */
    private static List<String> parameterTypes(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int start = i;
            while (desc.charAt(i) == '[') {
                i++;
            }
            if (desc.charAt(i) == 'L') {
                int end = desc.indexOf(';', i);
                if (start == i) {
                    String t = desc.substring(i + 1, end).replace('/', '.');
                    if (!t.equals("java.lang.Object")) {
                        out.add(t);
                    }
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    private record Annotation(String type, List<String> elements, Map<String, List<String>> classValues) {
        List<String> classes(String element) {
            return classValues.getOrDefault(element, List.of());
        }
    }

    /** RuntimeVisibleAnnotations of a member (or the class), the other attributes skipped. */
    private static List<Annotation> annotations(DataInputStream in, Object[] pool) throws IOException {
        List<Annotation> out = new ArrayList<>();
        int attrs = in.readUnsignedShort();
        for (int i = 0; i < attrs; i++) {
            String name = (String) pool[in.readUnsignedShort()];
            int len = in.readInt();
            if (!"RuntimeVisibleAnnotations".equals(name)) {
                in.skipNBytes(len);
                continue;
            }
            int n = in.readUnsignedShort();
            for (int k = 0; k < n; k++) {
                out.add(annotation(in, pool));
            }
        }
        return out;
    }

    private static Annotation annotation(DataInputStream in, Object[] pool) throws IOException {
        String type = (String) pool[in.readUnsignedShort()];
        int pairs = in.readUnsignedShort();
        List<String> elements = new ArrayList<>();
        Map<String, List<String>> classes = new HashMap<>();
        for (int i = 0; i < pairs; i++) {
            String element = (String) pool[in.readUnsignedShort()];
            elements.add(element);
            List<String> values = new ArrayList<>();
            elementValue(in, pool, values);
            classes.put(element, values);
        }
        return new Annotation(type, elements, classes);
    }

    /** Reads one element value; class literals ({@code Foo.class}) are collected as class names. */
    private static void elementValue(DataInputStream in, Object[] pool, List<String> classes) throws IOException {
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 'c' -> {
                String d = (String) pool[in.readUnsignedShort()];
                if (d.startsWith("L") && d.endsWith(";")) {
                    classes.add(d.substring(1, d.length() - 1).replace('/', '.'));
                }
            }
            case 'e' -> {
                in.readUnsignedShort();
                in.readUnsignedShort();
            }
            case '@' -> annotation(in, pool);
            case '[' -> {
                int n = in.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    elementValue(in, pool, classes);
                }
            }
            default -> in.readUnsignedShort();
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attrs = in.readUnsignedShort();
        for (int i = 0; i < attrs; i++) {
            in.readUnsignedShort();
            in.skipNBytes(in.readInt());
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
