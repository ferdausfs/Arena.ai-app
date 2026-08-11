package com.federal.arenaai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM unit tests for {@link UrlPolicy}.
 *
 * <p>{@code UrlPolicy} is the single decision point for "does this navigation stay inside the app
 * or go to the browser", which makes it the one piece of this app where a wrong answer is a
 * security problem rather than a cosmetic one: a host that is wrongly kept internal renders inside
 * the app's cookie jar. These tests need no emulator — the class touches no Android framework
 * type, only the generated {@code BuildConfig} constants.
 *
 * <p>Run with {@code ./gradlew testDebugUnitTest}.
 */
public class UrlPolicyTest {

    // --- Configuration sanity -------------------------------------------------------------

    @Test
    public void buildConfigProvidesTheExpectedSite() {
        assertEquals("arena.ai", UrlPolicy.PRIMARY_HOST);
        assertEquals("https://arena.ai/", UrlPolicy.START_URL);
    }

    @Test
    public void startUrlPointsAtThePrimaryHost() {
        assertTrue("START_URL must be an https URL on PRIMARY_HOST",
                UrlPolicy.START_URL.startsWith("https://" + UrlPolicy.PRIMARY_HOST));
    }

    // --- Schemes --------------------------------------------------------------------------

    @Test
    public void httpAndHttpsAreWebSchemes() {
        assertTrue(UrlPolicy.isWebScheme("https"));
        assertTrue(UrlPolicy.isWebScheme("http"));
    }

    @Test
    public void schemeMatchingIsCaseInsensitive() {
        assertTrue(UrlPolicy.isWebScheme("HTTPS"));
        assertTrue(UrlPolicy.isWebScheme("Http"));
    }

    @Test
    public void nonWebSchemesAreRejected() {
        assertFalse(UrlPolicy.isWebScheme("mailto"));
        assertFalse(UrlPolicy.isWebScheme("tel"));
        assertFalse(UrlPolicy.isWebScheme("intent"));
        assertFalse(UrlPolicy.isWebScheme("javascript"));
        assertFalse(UrlPolicy.isWebScheme("file"));
        assertFalse(UrlPolicy.isWebScheme("content"));
        assertFalse(UrlPolicy.isWebScheme("arena"));
        assertFalse(UrlPolicy.isWebScheme(""));
        assertFalse(UrlPolicy.isWebScheme(null));
    }

    // --- Primary host ---------------------------------------------------------------------

    @Test
    public void primaryHostAndItsSubdomainsMatch() {
        assertTrue(UrlPolicy.isPrimaryHost("arena.ai"));
        assertTrue(UrlPolicy.isPrimaryHost("www.arena.ai"));
        assertTrue(UrlPolicy.isPrimaryHost("app.arena.ai"));
        assertTrue(UrlPolicy.isPrimaryHost("cdn.assets.arena.ai"));
    }

    @Test
    public void primaryHostMatchingIsCaseInsensitiveAndTolerantOfTheRootDot() {
        assertTrue(UrlPolicy.isPrimaryHost("Arena.AI"));
        assertTrue(UrlPolicy.isPrimaryHost("WWW.ARENA.AI"));
        // "arena.ai." is the fully-qualified form of the same name.
        assertTrue(UrlPolicy.isPrimaryHost("arena.ai."));
        assertTrue(UrlPolicy.isPrimaryHost("  arena.ai  "));
    }

    /**
     * The suffix check must be anchored on a dot. Without that, an attacker who registers
     * "notarena.ai" or "arena.ai.evil.com" would get their page rendered inside the app.
     */
    @Test
    public void lookalikeHostsAreNotTreatedAsThePrimaryHost() {
        assertFalse(UrlPolicy.isPrimaryHost("notarena.ai"));
        assertFalse(UrlPolicy.isPrimaryHost("arena.ai.evil.com"));
        assertFalse(UrlPolicy.isPrimaryHost("evil-arena.ai"));
        assertFalse(UrlPolicy.isPrimaryHost("arena.aid"));
        assertFalse(UrlPolicy.isPrimaryHost("arena.a"));
        assertFalse(UrlPolicy.isPrimaryHost("xn--arena-ai"));
        assertFalse(UrlPolicy.isPrimaryHost(""));
        assertFalse(UrlPolicy.isPrimaryHost(null));
    }

    // --- Auth hosts -----------------------------------------------------------------------

    @Test
    public void allowListedIdentityProvidersMatch() {
        assertTrue(UrlPolicy.isAuthHost("accounts.google.com"));
        assertTrue(UrlPolicy.isAuthHost("appleid.apple.com"));
        assertTrue(UrlPolicy.isAuthHost("github.com"));
        assertTrue(UrlPolicy.isAuthHost("login.microsoftonline.com"));
    }

    @Test
    public void subdomainsOfAllowListedProvidersMatch() {
        // Tenant-specific and regional sub-domains are normal in these flows.
        assertTrue(UrlPolicy.isAuthHost("tenant.okta.com"));
        assertTrue(UrlPolicy.isAuthHost("arena.auth0.com"));
        assertTrue(UrlPolicy.isAuthHost("clerk.arena.clerk.accounts.dev"));
        assertTrue(UrlPolicy.isAuthHost("abcdefgh.supabase.co"));
    }

    @Test
    public void unrelatedHostsAreNotAuthHosts() {
        assertFalse(UrlPolicy.isAuthHost("example.com"));
        assertFalse(UrlPolicy.isAuthHost("news.ycombinator.com"));
        assertFalse(UrlPolicy.isAuthHost("google.com"));          // parent of an entry, not an entry
        assertFalse(UrlPolicy.isAuthHost("evilgithub.com"));
        assertFalse(UrlPolicy.isAuthHost("github.com.evil.net"));
        assertFalse(UrlPolicy.isAuthHost(null));
    }

    // --- The combined decision --------------------------------------------------------------

    @Test
    public void primaryAndAuthNavigationsStayInTheApp() {
        assertTrue(UrlPolicy.shouldHandleInternally("https", "arena.ai"));
        assertTrue(UrlPolicy.shouldHandleInternally("https", "app.arena.ai"));
        assertTrue(UrlPolicy.shouldHandleInternally("https", "accounts.google.com"));
        assertTrue(UrlPolicy.shouldHandleInternally("http", "arena.ai"));
    }

    @Test
    public void thirdPartyWebNavigationsGoToTheBrowser() {
        assertFalse(UrlPolicy.shouldHandleInternally("https", "example.com"));
        assertFalse(UrlPolicy.shouldHandleInternally("https", "en.wikipedia.org"));
        assertFalse(UrlPolicy.shouldHandleInternally("http", "example.com"));
    }

    @Test
    public void nonWebSchemesNeverStayInTheApp() {
        // Even on the primary host: a mailto: or intent: URL is for another app, not the WebView.
        assertFalse(UrlPolicy.shouldHandleInternally("mailto", "arena.ai"));
        assertFalse(UrlPolicy.shouldHandleInternally("tel", "arena.ai"));
        assertFalse(UrlPolicy.shouldHandleInternally("intent", "arena.ai"));
        assertFalse(UrlPolicy.shouldHandleInternally("file", "arena.ai"));
    }

    @Test
    public void missingSchemeOrHostIsNeverHandledInternally() {
        assertFalse(UrlPolicy.shouldHandleInternally(null, "arena.ai"));
        assertFalse(UrlPolicy.shouldHandleInternally("https", null));
        assertFalse(UrlPolicy.shouldHandleInternally(null, null));
        assertFalse(UrlPolicy.shouldHandleInternally("https", ""));
    }
}
