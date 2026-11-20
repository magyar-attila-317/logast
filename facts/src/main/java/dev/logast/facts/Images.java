package dev.logast.facts;

/** Container image names as facts use them (#160, #161): {@code image:<repository>}, the tag apart. */
public final class Images {
    private Images() {
    }

    /** {@code registry/repo:tag} → [registry/repo, tag]; a digest stays with the repository; no tag → null. */
    public static String[] split(String image) {
        int at = image.indexOf('@');
        String base = at >= 0 ? image.substring(0, at) : image;
        int slash = base.lastIndexOf('/');
        int colon = base.lastIndexOf(':');
        if (colon > slash) {
            return new String[]{base.substring(0, colon), base.substring(colon + 1)};
        }
        return new String[]{base, at >= 0 ? image.substring(at + 1) : null};
    }
}
