package dev.logast.rules;

import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Message wording and fluent structured properties.
 * <ul>
 *   <li>MESSAGE_VAGUE: avoid-listed wording ("Processing...", "Done", "Error happened", "Publishing x") is reported;
 *       contractions ("Won't publish") are spelled out, and vocabulary terms capitalised mid-sentence ("No Coupon
 *       entries") are lower-cased, mechanically;</li>
 *   <li>LOG_KEY_VALUE_IN_MDC: {@code .addKeyValue("orderId", orderId)} on a fluent log call where that key is in MDC
 *       already: the duplicate property is removed.</li>
 * </ul>
 * Messages matching {@code logging.protectedMessages} (alert-linked) are never changed.
 */
final class MessageRules {
    private static final Map<String, String> CONTRACTIONS = new LinkedHashMap<>();

    static {
        CONTRACTIONS.put("won't", "will not");
        CONTRACTIONS.put("can't", "cannot");
        CONTRACTIONS.put("don't", "do not");
        CONTRACTIONS.put("doesn't", "does not");
        CONTRACTIONS.put("didn't", "did not");
        CONTRACTIONS.put("isn't", "is not");
        CONTRACTIONS.put("aren't", "are not");
        CONTRACTIONS.put("wasn't", "was not");
        CONTRACTIONS.put("weren't", "were not");
        CONTRACTIONS.put("couldn't", "could not");
        CONTRACTIONS.put("shouldn't", "should not");
        CONTRACTIONS.put("hasn't", "has not");
        CONTRACTIONS.put("haven't", "have not");
    }

    private static final Pattern CONTRACTION = Pattern.compile("(?i)\\b(" + String.join("|", CONTRACTIONS.keySet()) + ")\\b");

    private final RuleContext ctx;
    private final List<Pattern> vague;
    private final Set<String> terms = new HashSet<>();

    MessageRules(RuleContext ctx) {
        this.ctx = ctx;
        this.vague = ctx.policy.logging().vagueMessages().stream().map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE)).toList();
        ctx.policy.terms().forEach(t -> terms.add(t.toLowerCase(Locale.ROOT)));
    }

    void run() {
        for (MethodInfo m : ctx.methods()) {
            int n = 0;
            for (LogCall lc : m.facts().logs) {
                if (ctx.policy.logging().protectedMessage(MessageTemplate.text(lc.template()))) {
                    continue;   // alert-linked: expand-contract only, by hand
                }
                if (!(lc.template() instanceof J.Literal l) || !(l.getValue() instanceof String raw)) {
                    continue;
                }
                if (ctx.enabled(Rules.MESSAGE_VAGUE)) {
                    wording(m, lc, l, raw, n++);
                }
                if (ctx.enabled(Rules.LOG_KEY_VALUE_IN_MDC) && lc.fluent()) {
                    keyValues(m, lc, n++);
                }
            }
        }
    }

    private void wording(MethodInfo m, LogCall lc, J.Literal lit, String raw, int ordinal) {
        String text = MessageTemplate.compliantKeys(raw) != null ? MessageTemplate.compliantBase(raw) : MessageTemplate.stripPlaceholders(raw);
        int line = Pos.line(lit);
        if (ctx.ignored(m.file(), line, Rules.MESSAGE_VAGUE)) {
            return;
        }
        String fixed = capitalisedTerms(expandContractions(raw));
        if (!fixed.equals(raw)) {
            ctx.order(Rules.MESSAGE_VAGUE, m, line, "wording" + ordinal)
                    .title("Message wording: " + (CONTRACTION.matcher(raw).find() ? "contraction" : "business noun capitalised mid-sentence")
                            + " in \"" + Text.oneLine(raw, 60) + "\"")
                    .edit(Edit.replace(m.file().relPath(), Pos.start(lit), Pos.end(lit), Text.javaString(fixed)))
                    .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
            return;
        }
        String plain = text.strip();
        if (vague.stream().anyMatch(p -> p.matcher(plain).find())) {
            ctx.order(Rules.MESSAGE_VAGUE, m, line, "wording" + ordinal)
                    .title("Vague message \"" + Text.oneLine(raw, 60) + "\": it does not say what started, completed or failed")
                    .suggestion("Name the business operation and its outcome: \"<Subject> started / completed / failed / skipped\", "
                            + "e.g. \"Coupon income invoice publication completed [count={}]\".")
                    .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
        }
    }

    static String expandContractions(String s) {
        Matcher mt = CONTRACTION.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            String word = mt.group(1);
            String full = CONTRACTIONS.get(word.toLowerCase(Locale.ROOT));
            if (Character.isUpperCase(word.charAt(0))) {
                full = Character.toUpperCase(full.charAt(0)) + full.substring(1);
            }
            mt.appendReplacement(sb, Matcher.quoteReplacement(full));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    /** "No Coupon entries found" → "No coupon entries found": a vocabulary term is lower-case unless it starts a sentence. */
    String capitalisedTerms(String s) {
        Matcher mt = Pattern.compile("(?<=[a-z0-9,;] )([A-Z][a-z]+)\\b(?!\\s*[:=])").matcher(s);   // "User: {}" is a label
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            String w = mt.group(1);
            String lower = w.toLowerCase(Locale.ROOT);
            boolean term = terms.contains(lower) || lower.endsWith("s") && terms.contains(lower.substring(0, lower.length() - 1));
            mt.appendReplacement(sb, Matcher.quoteReplacement(term ? lower : w));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    /** log.atInfo().addKeyValue("orderId", orderId).log(..) with orderId already in MDC: drop the duplicate property. */
    private void keyValues(MethodInfo m, LogCall lc, int ordinal) {
        Set<String> inMdc = ctx.mdcInside(m);
        Map<String, String> aliases = ctx.policy.mdcAliases();
        for (Expression e = lc.call().getSelect(); e instanceof J.MethodInvocation mi; e = mi.getSelect()) {
            if (!mi.getSimpleName().equals("addKeyValue") || mi.getArguments().size() != 2
                    || !(mi.getArguments().getFirst() instanceof J.Literal k) || !(k.getValue() instanceof String key)) {
                continue;
            }
            String canonical = aliases.getOrDefault(key, ctx.policy.styledKey(key));
            if (!inMdc.contains(key) && !inMdc.contains(canonical)) {
                continue;
            }
            int line = Pos.line(mi.getName());
            if (ctx.ignored(m.file(), line, Rules.LOG_KEY_VALUE_IN_MDC) || mi.getSelect() == null) {
                continue;
            }
            OrderBuilder b = ctx.order(Rules.LOG_KEY_VALUE_IN_MDC, m, line, "kv" + ordinal + "|" + key)
                    .title("Structured property '" + key + "' repeats the MDC key " + canonical + ": every log line in this scope "
                            + "already carries it")
                    .edit(Edit.replace(m.file().relPath(), Pos.end(mi.getSelect()), Pos.end(mi), ""))
                    .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
            String wrap = ctx.plannedBy(m, canonical);
            if (wrap != null) {
                b.dependsOn(wrap);
            }
        }
    }
}
