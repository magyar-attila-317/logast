package dev.logast.rules;

import dev.logast.SpringProperties;
import dev.logast.orders.Edit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** The service's base application.yaml/.yml: where YAML fixes go. */
final class ServiceYaml {
    private ServiceYaml() {
    }

    /** Path relative to the project root (existing file, or the one to create). */
    static String path(RuleContext ctx) {
        List<Path> resources = SpringProperties.resourceDirs(ctx.a.project.root());
        Path dir = resources.isEmpty() ? ctx.a.project.root().resolve("src/main/resources") : resources.getFirst();
        Path yml = Files.exists(dir.resolve("application.yaml")) ? dir.resolve("application.yaml") : dir.resolve("application.yml");
        return ctx.a.project.root().relativize(yml).toString().replace('\\', '/');
    }

    /** Merges {@code snippet} (nested YAML) into the file: new keys are added, existing values replaced. */
    static void merge(RuleContext ctx, OrderBuilder b, String snippet) {
        merge(ctx, b, snippet, false);
    }

    /** @param overwrite the snippet's values replace existing ones (for fixes that change a setting) */
    static void merge(RuleContext ctx, OrderBuilder b, String snippet, boolean overwrite) {
        String rel = path(ctx);
        Path file = ctx.a.project.root().resolve(rel);
        if (Files.exists(file)) {
            String text = SetupRules.read(file);
            b.edit(Edit.replace(rel, 0, text.length(), SetupRules.mergeYaml(text, snippet, overwrite)));
        } else {
            b.edit(Edit.create(rel, snippet));
        }
    }

    /** True when any application*.yml/.properties mentions {@code text} (profiles and env-specific files included). */
    static boolean mentioned(RuleContext ctx, String text) {
        for (Path dir : SpringProperties.resourceDirs(ctx.a.project.root())) {
            try (var files = Files.list(dir)) {
                if (files.filter(p -> p.getFileName().toString().startsWith("application"))
                        .anyMatch(p -> SetupRules.read(p).contains(text))) {
                    return true;
                }
            } catch (java.io.IOException e) {
                return false;
            }
        }
        return false;
    }
}
