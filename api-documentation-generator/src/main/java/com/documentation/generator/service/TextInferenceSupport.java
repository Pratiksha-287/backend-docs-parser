package com.documentation.generator.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure text helpers used to (a) normalise descriptions taken from the
 * specification and (b) generate short fallback descriptions when the
 * specification has none. Generated text is always flagged by the caller
 * (descriptionInferred = true) so consumers can tell it apart.
 */
final class TextInferenceSupport {

    private static final Pattern SPRINGFOX_SUFFIX = Pattern.compile(
            "Using(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE)(_\\d+)?$");

    private static final Pattern NUMERIC_SUFFIX = Pattern.compile("_\\d+$");

    private static final Pattern VERSION_SEGMENT =
            Pattern.compile("v\\d+(\\.\\d+)*", Pattern.CASE_INSENSITIVE);

    private static final Set<String> BARE_VERBS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options",
            "trace", "handle", "execute", "process", "index", "call", "list",
            "update", "create", "find", "save", "remove", "add", "all");

    private static final Set<String> TAG_SUFFIXES = Set.of(
            "controller", "rest controller", "resource", "api", "endpoint",
            "endpoints");

    private TextInferenceSupport() {
    }

    /** Trims, collapses spaces and returns null for blank text. */
    static String clean(String text) {

        if (text == null) {
            return null;
        }

        String cleaned = text.replace("\r", "")
                .strip()
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n");

        return cleaned.isEmpty() ? null : cleaned;
    }

    /** "user-controller" / "UserController" -> "User". */
    static String humanizeTag(String tag) {

        List<String> words = splitWords(tag);

        while (words.size() > 1
                && TAG_SUFFIXES.contains(lastWords(words))) {

            words = new ArrayList<>(words.subList(0, words.size() - suffixLength(words)));
        }

        if (words.isEmpty()) {
            return tag;
        }

        return capitalize(String.join(" ", words));
    }

    private static String lastWords(List<String> words) {

        int n = words.size();

        if (n >= 2 && TAG_SUFFIXES.contains(
                words.get(n - 2) + " " + words.get(n - 1))) {
            return words.get(n - 2) + " " + words.get(n - 1);
        }

        return words.get(n - 1);
    }

    private static int suffixLength(List<String> words) {
        return lastWords(words).contains(" ") ? 2 : 1;
    }

    /** Splits camelCase, snake_case, kebab-case and spaces to lower case words. */
    static List<String> splitWords(String text) {

        List<String> words = new ArrayList<>();

        if (text == null) {
            return words;
        }

        String spaced = text
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .trim();

        if (spaced.isEmpty()) {
            return words;
        }

        for (String word : spaced.split(" ")) {
            words.add(word.toLowerCase(Locale.ROOT));
        }

        return words;
    }

    /** Operation id used when the specification does not declare one. */
    static String syntheticOperationId(String method, String path) {

        StringBuilder id = new StringBuilder(method.toLowerCase(Locale.ROOT));

        for (String segment : pathSegments(path)) {

            if (isParam(segment)) {
                id.append("_by_").append(paramName(segment));
            } else {
                id.append('_').append(segment);
            }
        }

        return id.toString().replaceAll("[^A-Za-z0-9_]+", "_");
    }

    /**
     * Fallback summary. The operation id (usually the Spring method name,
     * e.g. getUserById) is the most faithful source; the HTTP method and
     * path are only used when the id carries no meaning (get_1, ...).
     */
    static String inferSummary(String method, String path, String operationId) {

        if (operationId != null) {

            String cleaned = SPRINGFOX_SUFFIX.matcher(operationId).replaceAll("");
            cleaned = NUMERIC_SUFFIX.matcher(cleaned).replaceAll("");

            List<String> words = splitWords(cleaned);

            boolean meaningful = words.size() >= 2
                    || (words.size() == 1 && !BARE_VERBS.contains(words.get(0)));

            if (meaningful) {
                return capitalize(String.join(" ", words));
            }
        }

        return summaryFromPath(method, path);
    }

    private static String summaryFromPath(String method, String path) {

        List<String> segments = pathSegments(path);

        if (segments.isEmpty()) {
            return method + " root";
        }

        String last = segments.get(segments.size() - 1);
        boolean item = isParam(last);

        int resourceIndex = item ? segments.size() - 2 : segments.size() - 1;
        String resource = resourceIndex >= 0
                ? segments.get(resourceIndex) : "resource";
        String singular = singular(humanize(resource));
        String plural = humanize(resource);

        // nearest static segment before a path parameter = the parent
        String parent = null;

        for (int i = resourceIndex - 1; i >= 0; i--) {
            if (isParam(segments.get(i)) && i - 1 >= 0
                    && !isParam(segments.get(i - 1))) {
                parent = singular(humanize(segments.get(i - 1)));
                break;
            }
        }

        String of = parent == null ? "" : " of " + withArticle(parent);

        return switch (method.toUpperCase(Locale.ROOT)) {

            case "GET" -> item
                    ? "Retrieves " + withArticle(singular)
                            + " by " + humanize(paramName(last)) + of
                    : "Lists " + plural + of;

            case "POST" -> "Creates " + withArticle(singular) + of;

            case "PUT" -> "Replaces " + withArticle(singular)
                    + (item ? " by " + humanize(paramName(last)) : "") + of;

            case "PATCH" -> "Updates " + withArticle(singular)
                    + (item ? " by " + humanize(paramName(last)) : "") + of;

            case "DELETE" -> "Deletes " + withArticle(singular)
                    + (item ? " by " + humanize(paramName(last)) : "") + of;

            default -> method + " " + plural + of;
        };
    }

    static List<String> pathSegments(String path) {

        List<String> segments = new ArrayList<>();

        if (path == null) {
            return segments;
        }

        for (String segment : path.split("/")) {

            if (segment.isBlank()
                    || segment.equalsIgnoreCase("api")
                    || VERSION_SEGMENT.matcher(segment).matches()) {
                continue;
            }

            segments.add(segment);
        }

        return segments;
    }

    static boolean isParam(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    static String paramName(String segment) {
        return isParam(segment)
                ? segment.substring(1, segment.length() - 1) : segment;
    }

    static String humanize(String text) {
        return String.join(" ", splitWords(text));
    }

    static String singular(String word) {

        if (word == null || word.length() < 4) {
            return word;
        }

        // only the last word of a phrase is inflected
        int space = word.lastIndexOf(' ');
        String head = space >= 0 ? word.substring(0, space + 1) : "";
        String tail = space >= 0 ? word.substring(space + 1) : word;

        if (tail.endsWith("ies") && tail.length() > 4) {
            tail = tail.substring(0, tail.length() - 3) + "y";
        } else if (tail.endsWith("sses") || tail.endsWith("shes")
                || tail.endsWith("ches") || tail.endsWith("xes")) {
            tail = tail.substring(0, tail.length() - 2);
        } else if (tail.endsWith("s") && !tail.endsWith("ss")
                && !tail.endsWith("us") && !tail.endsWith("is")) {
            tail = tail.substring(0, tail.length() - 1);
        }

        return head + tail;
    }

    static String withArticle(String noun) {

        if (noun == null || noun.isEmpty()) {
            return "a resource";
        }

        String lower = noun.toLowerCase(Locale.ROOT);

        boolean soundsLikeConsonant = lower.startsWith("uni")
                || lower.startsWith("use") || lower.startsWith("usu")
                || lower.startsWith("eu") || lower.startsWith("one");

        boolean vowel = "aeiou".indexOf(lower.charAt(0)) >= 0
                && !soundsLikeConsonant;

        return (vowel ? "an " : "a ") + noun;
    }

    static String capitalize(String text) {

        if (text == null || text.isEmpty()) {
            return text;
        }

        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Joins up to {@code limit} items and appends "(+N more)". */
    static String listWithLimit(List<String> items, int limit) {

        if (items.size() <= limit) {
            return String.join(", ", items);
        }

        return String.join(", ", items.subList(0, limit))
                + " (+" + (items.size() - limit) + " more)";
    }
}
