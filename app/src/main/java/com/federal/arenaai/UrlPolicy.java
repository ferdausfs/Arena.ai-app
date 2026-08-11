package com.federal.arenaai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides which URLs are allowed to stay inside the in-app {@link android.webkit.WebView} and
 * which ones must be handed over to the device's default browser.
 *
 * <p>The rules are intentionally plain Java (no Android framework types) so they can be covered by
 * fast JVM unit tests — see {@code app/src/test/java/com/federal/arenaai/UrlPolicyTest.java}.
 *
 * <p><b>Policy</b>
 *
 * <ul>
 *   <li>{@code arena.ai} and any of its sub-domains load in the app. This is the app's own site.
 *   <li>A short, explicit list of identity-provider hosts also loads in the app. Sign-in flows
 *       redirect through those hosts and then back to {@code arena.ai}; if they were pushed out to
 *       the browser the resulting session cookie would be set in the browser instead of in the
 *       app, and the user could never finish logging in.
 *   <li>Everything else — a link to a news article, a social network, a documentation site — is
 *       opened in the user's default browser, which is what a link out of an app should do.
 *   <li>Non-web schemes ({@code mailto:}, {@code tel:}, {@code intent:}, custom app schemes) are
 *       never loaded by the WebView; they are dispatched to whichever app handles them.
 * </ul>
 */
public final class UrlPolicy {

    /** The site this app wraps. Comes from {@code app/build.gradle} so it is defined once. */
    public static final String PRIMARY_HOST = BuildConfig.ARENA_HOST;

    /** The URL the session starts at. Comes from {@code app/build.gradle}. */
    public static final String START_URL = BuildConfig.ARENA_START_URL;

    /**
     * Identity providers that must be able to run inside the app so that sign-in can complete and
     * hand the session back to {@code arena.ai}.
     *
     * <p>Matching is "host is exactly this, or is a sub-domain of this". The list is deliberately
     * short: every extra entry is a host that can render inside the app's cookie jar.
     */
    private static final Set<String> AUTH_HOSTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(
                    "accounts.google.com",
                    "accounts.youtube.com",
                    "appleid.apple.com",
                    "github.com",
                    "login.microsoftonline.com",
                    "login.live.com",
                    "auth0.com",
                    "okta.com",
                    "clerk.accounts.dev",
                    "clerk.com",
                    "supabase.co",
                    "firebaseapp.com",
                    "stytch.com",
                    "workos.com")));

    private UrlPolicy() {
        // No instances.
    }

    /** True for the two schemes a WebView is allowed to render here. */
    public static boolean isWebScheme(@Nullable String scheme) {
        if (scheme == null) {
            return false;
        }
        String lower = scheme.toLowerCase(Locale.US);
        return "https".equals(lower) || "http".equals(lower);
    }

    /** True when {@code host} is {@value #PRIMARY_HOST} or one of its sub-domains. */
    public static boolean isPrimaryHost(@Nullable String host) {
        return matches(host, PRIMARY_HOST);
    }

    /** True when {@code host} belongs to one of the allow-listed identity providers. */
    public static boolean isAuthHost(@Nullable String host) {
        String normalized = normalize(host);
        if (normalized == null) {
            return false;
        }
        for (String authHost : AUTH_HOSTS) {
            if (matches(normalized, authHost)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decides whether a navigation to {@code scheme}://{@code host}/… stays in the WebView.
     *
     * @param scheme the URL scheme, may be {@code null}
     * @param host   the URL host, may be {@code null}
     * @return {@code true} to keep the navigation in the app, {@code false} to hand it to the
     * default browser (or, for non-web schemes, to the app that handles them)
     */
    public static boolean shouldHandleInternally(@Nullable String scheme, @Nullable String host) {
        if (!isWebScheme(scheme)) {
            return false;
        }
        return isPrimaryHost(host) || isAuthHost(host);
    }

    /**
     * Lower-cases a host and strips a trailing dot (the root-label form {@code arena.ai.} is a
     * valid, equivalent host name).
     */
    @Nullable
    private static String normalize(@Nullable String host) {
        if (host == null) {
            return null;
        }
        String normalized = host.trim().toLowerCase(Locale.US);
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /** True when {@code host} equals {@code candidate} or is a sub-domain of it. */
    private static boolean matches(@Nullable String host, @NonNull String candidate) {
        String normalized = normalize(host);
        if (normalized == null) {
            return false;
        }
        return normalized.equals(candidate) || normalized.endsWith("." + candidate);
    }
}
