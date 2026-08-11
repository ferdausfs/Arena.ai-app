package com.federal.arenaai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

object WebViewManager {
    private var webView: WebView? = null
    private var mutableContext: MutableContextWrapper? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun getWebView(context: Context): WebView {
        if (webView == null) {
            mutableContext = MutableContextWrapper(context)
            webView = WebView(mutableContext!!).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    userAgentString = userAgentString.replace("; wv", "") // Optional: Remove wv to act more like a standard browser
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url ?: return false
                        val host = url.host ?: return false

                        // Keep internal links in the WebView
                        if (host.contains("arena.ai")) {
                            return false
                        }

                        // Open external links in default browser
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, url).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            mutableContext?.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return true
                    }
                    
                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        // Could handle error state here
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    // Handles loading indicators, permissions, etc.
                }

                loadUrl("https://arena.ai")
            }
        } else {
            // Update the context to the new Activity
            mutableContext?.baseContext = context
        }
        return webView!!
    }

    fun canGoBack(): Boolean {
        return webView?.canGoBack() == true
    }

    fun goBack() {
        webView?.goBack()
    }
}
