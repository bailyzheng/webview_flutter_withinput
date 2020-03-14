// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.
package io.flutter.plugins.webviewflutter

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.core.content.FileProvider
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.platform.PlatformView
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

class FlutterWebView @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1) internal constructor(
        context: Context,
        messenger: BinaryMessenger?,
        id: Int,
        params: Map<String?, Any?>,
        containerView: View?) : PlatformView, MethodCallHandler {
    private val webView: InputAwareWebView
    private val methodChannel: MethodChannel
    private val flutterWebViewClient: FlutterWebViewClient
    private val platformThreadHandler: Handler
    override fun getView(): View {
        return webView
    }

    // @Override
// This is overriding a method that hasn't rolled into stable Flutter yet. Including the
// annotation would cause compile time failures in versions of Flutter too old to include the new
// method. However leaving it raw like this means that the method will be ignored in old versions
// of Flutter but used as an override anyway wherever it's actually defined.
// TODO(mklim): Add the @Override annotation once flutter/engine#9727 rolls to stable.
    override fun onInputConnectionUnlocked() {
        webView.unlockInputConnection()
    }

    // @Override
// This is overriding a method that hasn't rolled into stable Flutter yet. Including the
// annotation would cause compile time failures in versions of Flutter too old to include the new
// method. However leaving it raw like this means that the method will be ignored in old versions
// of Flutter but used as an override anyway wherever it's actually defined.
// TODO(mklim): Add the @Override annotation once flutter/engine#9727 rolls to stable.
    override fun onInputConnectionLocked() {
        webView.lockInputConnection()
    }

    // @Override
// This is overriding a method that hasn't rolled into stable Flutter yet. Including the
// annotation would cause compile time failures in versions of Flutter too old to include the new
// method. However leaving it raw like this means that the method will be ignored in old versions
// of Flutter but used as an override anyway wherever it's actually defined.
// TODO(mklim): Add the @Override annotation once stable passes v1.10.9.
    override fun onFlutterViewAttached(flutterView: View) {
        webView.setContainerView(flutterView)
    }

    // @Override
// This is overriding a method that hasn't rolled into stable Flutter yet. Including the
// annotation would cause compile time failures in versions of Flutter too old to include the new
// method. However leaving it raw like this means that the method will be ignored in old versions
// of Flutter but used as an override anyway wherever it's actually defined.
// TODO(mklim): Add the @Override annotation once stable passes v1.10.9.
    override fun onFlutterViewDetached() {
        webView.setContainerView(null)
    }

    override fun onMethodCall(methodCall: MethodCall, result: MethodChannel.Result) {
        when (methodCall.method) {
            "loadUrl" -> loadUrl(methodCall, result)
            "updateSettings" -> updateSettings(methodCall, result)
            "canGoBack" -> canGoBack(result)
            "canGoForward" -> canGoForward(result)
            "goBack" -> goBack(result)
            "goForward" -> goForward(result)
            "reload" -> reload(result)
            "currentUrl" -> currentUrl(result)
            "evaluateJavascript" -> evaluateJavaScript(methodCall, result)
            "addJavascriptChannels" -> addJavaScriptChannels(methodCall, result)
            "removeJavascriptChannels" -> removeJavaScriptChannels(methodCall, result)
            "clearCache" -> clearCache(result)
            "getTitle" -> getTitle(result)
            "customCommandToWebview" -> customCommandToWebview(methodCall, result)
            else -> result.notImplemented()
        }
    }

    private fun loadUrl(methodCall: MethodCall, result: MethodChannel.Result) {
        val request = methodCall.arguments as Map<String, Any>
        val url = request["url"] as String?
        var headers = request["headers"] as Map<String?, String?>?
        if (headers == null) {
            headers = emptyMap<String?, String>()
        }
        webView.loadUrl(url, headers)
        result.success(null)
    }

    private fun canGoBack(result: MethodChannel.Result) {
        result.success(webView.canGoBack())
    }

    private fun canGoForward(result: MethodChannel.Result) {
        result.success(webView.canGoForward())
    }

    private fun goBack(result: MethodChannel.Result) {
        if (webView.canGoBack()) {
            webView.goBack()
        }
        result.success(null)
    }

    private fun goForward(result: MethodChannel.Result) {
        if (webView.canGoForward()) {
            webView.goForward()
        }
        result.success(null)
    }

    private fun reload(result: MethodChannel.Result) {
        webView.reload()
        result.success(null)
    }

    private fun currentUrl(result: MethodChannel.Result) {
        result.success(webView.url)
    }

    private fun updateSettings(methodCall: MethodCall, result: MethodChannel.Result) {
        applySettings(methodCall.arguments as Map<String, Any>)
        result.success(null)
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private fun evaluateJavaScript(methodCall: MethodCall, result: MethodChannel.Result) {
        val jsString = methodCall.arguments as String
                ?: throw UnsupportedOperationException("JavaScript string cannot be null")
        webView.evaluateJavascript(
                jsString
        ) { value -> result.success(value) }
    }

    private fun addJavaScriptChannels(methodCall: MethodCall, result: MethodChannel.Result) {
        val channelNames = methodCall.arguments as List<String>
        registerJavaScriptChannelNames(channelNames)
        result.success(null)
    }

    private fun removeJavaScriptChannels(methodCall: MethodCall, result: MethodChannel.Result) {
        val channelNames = methodCall.arguments as List<String>
        for (channelName in channelNames) {
            webView.removeJavascriptInterface(channelName)
        }
        result.success(null)
    }

    private fun clearCache(result: MethodChannel.Result) {
        webView.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        result.success(null)
    }

    private fun getTitle(result: MethodChannel.Result) {
        result.success(webView.title)
    }
    
    private fun customCommandToWebview(methodCall: MethodCall, result: MethodChannel.Result) {
        val files = mutableListOf<String>()
        val jsonObj = JSONObject(methodCall.arguments.toString())
        if (jsonObj.get("method").toString() == "pick_file") {
            try {
                val arrayObj: JSONArray? = jsonObj.getJSONArray("result")
                if (arrayObj != null) {
                    for (i in 0 until arrayObj!!.length()) {
                        files.add(arrayObj[i]!!.toString())
                    }
                    Log.v(TAG, "files is $files")
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }

            if (files.isNotEmpty()) {
                fileCallback?.onReceiveValue(
                        files.map {
                            FileProvider.getUriForFile(context!!, "${context!!.packageName}.fileProvider", File(it))
                        }.toTypedArray()
                )
            } else {
                fileCallback?.onReceiveValue(null)
            }
            result.success("SUCCESS")
        } else if (jsonObj.get("method").toString() == "font_response") {
            val res = jsonObj.get("result").toString()
            webView.loadUrl("javascript:onFontQueryResult('$res')")
            result.success("SUCCESS")
        }
    }

    private fun applySettings(settings: Map<String, Any>?) {
        for (key in settings!!.keys) {
            when (key) {
                "jsMode" -> updateJsMode((settings[key] as Int?)!!)
                "hasNavigationDelegate" -> {
                    val hasNavigationDelegate = settings[key] as Boolean
                    val webViewClient = flutterWebViewClient.createWebViewClient(hasNavigationDelegate)
                    webView.webViewClient = webViewClient
                }
                "debuggingEnabled" -> {
                    val debuggingEnabled = settings[key] as Boolean
                    WebView.setWebContentsDebuggingEnabled(debuggingEnabled)
                }
                "gestureNavigationEnabled" -> {
                }
                "userAgent" -> updateUserAgent(settings[key] as String?)
                else -> throw IllegalArgumentException("Unknown WebView setting: $key")
            }
        }
    }

    private fun updateJsMode(mode: Int) {
        when (mode) {
            0 -> webView.settings.javaScriptEnabled = false
            1 -> webView.settings.javaScriptEnabled = true
            else -> throw IllegalArgumentException("Trying to set unknown JavaScript mode: $mode")
        }
    }

    private fun updateAutoMediaPlaybackPolicy(mode: Int) { // This is the index of the AutoMediaPlaybackPolicy enum, index 1 is always_allow, for all
// other values we require a user gesture.
        val requireUserGesture = mode != 1
        webView.settings.mediaPlaybackRequiresUserGesture = requireUserGesture
    }

    private fun registerJavaScriptChannelNames(channelNames: List<String>?) {
        for (channelName in channelNames!!) {
            webView.addJavascriptInterface(
                    JavaScriptChannel(methodChannel, channelName, platformThreadHandler), channelName)
        }
    }

    private fun updateUserAgent(userAgent: String?) {
        webView.settings.userAgentString = userAgent
    }

    override fun dispose() {
        methodChannel.setMethodCallHandler(null)
        webView.dispose()
        webView.destroy()
    }

    companion object {
        private const val TAG = "FlutterWebView"
        private const val JS_CHANNEL_NAMES_FIELD = "javascriptChannelNames"
    }

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    fun setChromeClient(mWebView: WebView) {
        mWebView!!.webChromeClient = object : WebChromeClient() {
            fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.proceed()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
            }

//            override fun getVideoLoadingProgressView(): View {
//                val frameLayout = FrameLayout(context)
//                frameLayout.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
//                return frameLayout
//            }

//            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
//                showCustomView(view, callback)
//            }

//            override fun onHideCustomView() {
//                hideCustomView()
//            }

            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                Log.v(TAG, "onShowFileChooser")
                fileCallback = filePathCallback
                var handled = false
                if (fileChooserParams?.acceptTypes != null) {
                    Log.v(TAG, "accept type is not null")
                    handled = true
                    val jsonObject = JSONObject()
                    jsonObject.put("method", "pick_file")
                    val jsonArray = JSONArray()
                    for (i in 0 until fileChooserParams.acceptTypes.size) {
                        jsonArray.put(fileChooserParams.acceptTypes[i])
                    }
                    jsonObject.put("acceptTypes", jsonArray)
                    methodChannel.invokeMethod("onCustomCommand", jsonObject.toString())
                }
                if (!handled) {
                    fileCallback?.onReceiveValue(null)
                }

                return true
            }
        }
        
    }
    
    private fun startChooseImage() {
        val pickIntent = Intent(Intent.ACTION_PICK)
//        val pickIntent = Intent(Intent.ACTION_GET_CONTENT)
        pickIntent.type = "image/*"
//        pickIntent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            pickIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
//        context.startActivityForResult(pickIntent, 0)
        ChooseFileIns.startChoose(pickIntent, ChooseFileIns.REQUEST_CODE, object : BiConsumer<Int, Intent?> {
            override fun accept(resultCode: Int, data: Intent?) {
                Log.v(TAG, "enter accept")
                if (resultCode == Activity.RESULT_OK) {
                    val curImageUri = data?.data ?: return
//                    val path = FileUriUtils.getFilePathByUri(this, curImageUri)
//                    Log.v(TAG, "path is $path")
                    fileCallback ?. onReceiveValue(arrayOf(curImageUri))
                } else {
                    fileCallback ?. onReceiveValue(null)
                }
            }
        })
    }

    private fun loadImages(num: Int) : List<Uri>? {

        val retList = mutableListOf<Uri>()
        val mResolver = context?.contentResolver
        val cursor = mResolver?.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null, null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")
                ?: return retList
        var i = 0
        if (cursor.moveToFirst()) {
            do {
                val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val dateAdded = cursor.getLong(cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED))
                Log.v(TAG, "data added is $dateAdded")
                retList.add(Uri.parse(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString() + "/" + cursor.getInt(idIndex)))
                i++
            } while (cursor.moveToNext() && i < num)
        }

        return retList
    }

    private var context: Context? = null
    init {
        this.context = context
        val displayListenerProxy = DisplayListenerProxy()
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayListenerProxy.onPreWebViewInitialization(displayManager)
        webView = InputAwareWebView(context, containerView)
        displayListenerProxy.onPostWebViewInitialization(displayManager)
        platformThreadHandler = Handler(context.mainLooper)
        
        webView.settings.useWideViewPort = true //将图片调整到适合webview的大小
        webView.settings.loadWithOverviewMode = true // 缩放至屏幕的大小
        webView.settings.setSupportZoom(true)

        // 便页面支持缩放
        webView.settings.javaScriptEnabled = true //支持js
        webView.settings.setSupportZoom(true) //支持缩放
        // Allow local storage.
        webView.settings.domStorageEnabled = true
        webView.settings.setSupportMultipleWindows(true)// 新加
        
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.allowContentAccess = true
        webView.settings.allowFileAccess = true
        webView.settings.allowFileAccessFromFileURLs = true
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.settings.setAppCacheEnabled(true)
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        
        setChromeClient(webView)
        methodChannel = MethodChannel(messenger, "plugins.flutter.io/webview_$id")
        methodChannel.setMethodCallHandler(this)
        flutterWebViewClient = FlutterWebViewClient(methodChannel)
        applySettings(params["settings"] as Map<String, Any>?)
        if (params.containsKey(JS_CHANNEL_NAMES_FIELD)) {
            registerJavaScriptChannelNames(params[JS_CHANNEL_NAMES_FIELD] as List<String>?)
        }
        updateAutoMediaPlaybackPolicy((params["autoMediaPlaybackPolicy"] as Int?)!!)
        if (params.containsKey("userAgent")) {
            val userAgent = params["userAgent"] as String?
            updateUserAgent(userAgent)
        }
        if (params.containsKey("initialUrl")) {
            val url = params["initialUrl"] as String?
            webView.loadUrl(url)
        }
    }
}