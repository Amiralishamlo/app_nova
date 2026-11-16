package site.novasinema.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import site.novasinema.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val BASE_URL = "https://novasinema.site"
        private const val HOST_DOMAIN = "novasinema.site"
        private const val EXIT_CONFIRM_INTERVAL_MS = 2000L
    }

    private lateinit var binding: ActivityMainBinding

    // مدیریت پخش تمام‌صفحه ویدیو (Fullscreen HTML5 Video)
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // وضعیت بارگذاری و خطا
    private var isInitialLoadComplete = false
    private var hasReceivedMainFrameError = false
    private var lastFailingUrl: String = BASE_URL
    private var lastBackPressTime: Long = 0L

    // نگهداری موقت درخواست دانلود برای اندروید ۸ و ۹ در صورت نیاز به مجوز حافظه
    private var pendingDownload: PendingDownloadRequest? = null

    // آپلود فایل در فرم‌های سایت (مثلاً تغییر آواتار پروفایل یا ارسال تیکت)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private data class PendingDownloadRequest(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val request = pendingDownload
        pendingDownload = null
        if (isGranted && request != null) {
            enqueueSystemDownload(
                request.url,
                request.userAgent,
                request.contentDisposition,
                request.mimeType
            )
        } else if (!isGranted) {
            Toast.makeText(
                this,
                getString(R.string.storage_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback.onReceiveValue(uris)
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                // اگر کاربر در صفحه خطای قطع اینترنت بود، به محض وصل شدن اینترنت صفحه را خودکار بارگذاری کن
                if (binding.errorOverlay.visibility == View.VISIBLE) {
                    retryLoadingPage()
                } else {
                    binding.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSplashAnimation()
        setupSwipeToRefresh()
        setupErrorScreenActions()
        setupOptimizedWebView()
        setupBackNavigation()
        registerNetworkCallback()

        // بررسی لینک ورودی (Deep Link) یا بارگذاری آدرس اصلی سایت
        val initialUrl = extractDeepLinkUrl(intent) ?: BASE_URL
        lastFailingUrl = initialUrl

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
            hideSplashSmoothly()
        } else {
            loadTargetUrl(initialUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLinkUrl(intent)?.let { deepLink ->
            loadTargetUrl(deepLink)
        }
    }

    /**
     * استخراج آدرس معتبر در صورتی که کاربر روی لینک novasinema.site کلیک کرده باشد
     */
    private fun extractDeepLinkUrl(intent: Intent?): String? {
        val data: Uri = intent?.data ?: return null
        val host = data.host?.lowercase() ?: return null
        return if (host == HOST_DOMAIN || host.endsWith(".$HOST_DOMAIN")) {
            data.toString()
        } else {
            null
        }
    }

    /**
     * انیمیشن ملایم لوگوی اسپلش‌اسکرین هنگام بارگذاری اولیه
     */
    private fun setupSplashAnimation() {
        binding.splashLogo.scaleX = 0.88f
        binding.splashLogo.scaleY = 0.88f
        binding.splashLogo.alpha = 0.85f
        binding.splashLogo.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(750)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    /**
     * تنظیم قابلیت کشیدن صفحه به پایین (Pull-to-Refresh) بدون تداخل با اسکرول صفحه یا پخش ویدیو
     */
    private fun setupSwipeToRefresh() {
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.nova_accent_light),
            ContextCompat.getColor(this, R.color.nova_accent)
        )
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.cinema_surface_card)
        )

        // فقط زمانی که اسکرول دقیقاً در بالاترین نقطه صفحه است و ویدیو تمام‌صفحه نیست، اجازه رفرش بده
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            customVideoView != null || binding.webView.scrollY > 0
        }

        binding.swipeRefresh.setOnRefreshListener {
            if (isNetworkConnected()) {
                hasReceivedMainFrameError = false
                binding.webView.reload()
            } else {
                binding.swipeRefresh.isRefreshing = false
                showOfflineErrorOverlay()
            }
        }
    }

    /**
     * تنظیم دکمه‌های صفحه خطای عدم اتصال به اینترنت
     */
    private fun setupErrorScreenActions() {
        binding.btnRetry.setOnClickListener {
            retryLoadingPage()
        }

        binding.btnNetworkSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * پیکربندی بهینه و امن WebView برای اندروید ۸ به بالا
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupOptimizedWebView() {
        val webView = binding.webView
        webView.setBackgroundColor(Color.parseColor("#0B0D13"))
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // فعال‌سازی و حفظ کوکی‌ها برای ماندگاری لاگین کاربر در سایت نوا سینما
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // امکان پخش مستقیم تیزر و ویدیو بدون محدودیت ژست لمسی
            mediaPlaybackRequiresUserGesture = false

            // تطبیق کامل با صفحه موبایل
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // مدیریت پنجره‌ها برای جلوگیری از پاپ‌آپ‌های تبلیغاتی مزاحم
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)

            // امنیت فایل‌ها در WebView
            allowFileAccess = false
            allowContentAccess = true

            // پشتیبانی از لود منابع ویدیویی و تصاویر از سرورهای CDN مختلف
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // کش هوشمند برای بارگذاری سریع‌تر صفحات قبلی
            cacheMode = if (isNetworkConnected()) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }

            // بهینه‌سازی User-Agent (حذف نشانه wv برای سازگاری کامل با پلیرهای ویدیویی سایت)
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }

        // پل ارتباطی جاوااسکریپت برای تشخیص پخش ویدیو و روشن نگه داشتن صفحه (WakeLock)
        webView.addJavascriptInterface(VideoWakeLockBridge(), "NovaWakeLock")

        webView.webViewClient = createWebViewClient()
        webView.webChromeClient = createWebChromeClient()
        webView.setDownloadListener(createDownloadListener())
    }

    /**
     * مدیریت رویدادهای بارگذاری صفحه، خطاهای شبکه و امنیت لینک‌ها
     */
    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank() && url != "about:blank") {
                    lastFailingUrl = url
                }
                binding.topProgressBar.progress = 10
                binding.topProgressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefresh.isRefreshing = false
                binding.topProgressBar.visibility = View.GONE

                // ذخیره پایدار کوکی‌های نشست کاربر
                CookieManager.getInstance().flush()

                // تزریق اسکریپت سبک برای تشخیص پخش ویدیوهای داخل صفحه (روشن ماندن صفحه هنگام تماشا)
                injectVideoWakeLockMonitor()

                if (!hasReceivedMainFrameError) {
                    binding.errorOverlay.visibility = View.GONE
                    hideSplashSmoothly()
                    isInitialLoadComplete = true
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return false
                val host = uri.host?.lowercase() ?: ""

                // ۱. لینک‌های غیر HTTP/HTTPS (مانند تلگرام، اینستاگرام، ایمیل، تماس یا پلیر خارجی)
                if (scheme != "http" && scheme != "https") {
                    return openExternalSchemeSafely(uri)
                }

                // ۲. لینک‌های اصلی دامنه novasinema.site و زیردامنه‌های آن داخل اپ باز شوند
                if (host == HOST_DOMAIN || host.endsWith(".$HOST_DOMAIN")) {
                    return false
                }

                // ۳. لینک‌های شبکه‌های اجتماعی (تلگرام، اینستاگرام و...) در اپ مربوطه باز شوند
                if (host.contains("t.me") ||
                    host.contains("telegram.me") ||
                    host.contains("instagram.com")
                ) {
                    return openExternalBrowserOrApp(uri)
                }

                // سایر صفحات مجاز (مانند درگاه پرداخت یا سرور دانلود مستقیم)
                return false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // فقط اگر خطا مربوط به فریم اصلی صفحه است (نه یک عکس یا اسکریپت فرعی)، صفحه خطا را نشان بده
                if (request?.isForMainFrame == true) {
                    hasReceivedMainFrameError = true
                    binding.swipeRefresh.isRefreshing = false
                    binding.topProgressBar.visibility = View.GONE
                    request.url?.toString()?.let { failingUrl ->
                        if (failingUrl != "about:blank") {
                            lastFailingUrl = failingUrl
                        }
                    }
                    showOfflineErrorOverlay()
                }
            }
        }
    }

    /**
     * مدیریت پخش تمام‌صفحه ویدیو (چرخش خودکار به حالت افقی + روشن ماندن صفحه) و نوار پیشرفت
     */
    private fun createWebChromeClient(): WebChromeClient {
        return object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.topProgressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.topProgressBar.visibility = View.GONE
                } else if (binding.topProgressBar.visibility != View.VISIBLE) {
                    binding.topProgressBar.visibility = View.VISIBLE
                }

                // محو کردن اسپلش‌اسکرین به محض آماده شدن ساختار اصلی سایت (حداقل ۸۰٪)
                if (newProgress >= 80 && !hasReceivedMainFrameError) {
                    hideSplashSmoothly()
                }
            }

            /**
             * ورود به حالت تمام‌صفحه ویدیو (هنگامی که کاربر روی دکمه Fullscreen پلیر فیلم کلیک می‌کند)
             */
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                if (view == null) return

                customVideoView = view
                customViewCallback = callback

                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE
                binding.swipeRefresh.visibility = View.INVISIBLE

                // روشن نگه داشتن صفحه و چرخش خودکار به حالت افقی (Landscape)
                setKeepScreenAwake(true)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                setImmersiveFullscreenMode(true)
            }

            /**
             * خروج از حالت تمام‌صفحه ویدیو و بازگشت به حالت عادی
             */
            override fun onHideCustomView() {
                val view = customVideoView ?: return

                binding.fullscreenContainer.removeView(view)
                binding.fullscreenContainer.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE

                customVideoView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null

                // بازگرداندن چرخش صفحه و نوار وضعیت به حالت عادی
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                setImmersiveFullscreenMode(false)
                setKeepScreenAwake(false)
            }

            /**
             * مسدودسازی پاپ‌آپ‌های تبلیغاتی ناخواسته و هدایت لینک‌های معتبر خود سایت به همان صفحه
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val hitTestResult = view?.hitTestResult
                val targetUrl = hitTestResult?.extra
                if (!targetUrl.isNullOrBlank() && isUserGesture) {
                    val uri = Uri.parse(targetUrl)
                    val host = uri.host?.lowercase() ?: ""
                    if (host == HOST_DOMAIN || host.endsWith(".$HOST_DOMAIN")) {
                        view.loadUrl(targetUrl)
                        return false
                    }
                }
                return false
            }

            /**
             * پشتیبانی از انتخاب فایل در صورت نیاز در پنل کاربری سایت
             */
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                return try {
                    val intent = fileChooserParams?.createIntent() ?: return false
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }
    }

    /**
     * مدیریت دانلود مستقیم فیلم، سریال و زیرنویس از داخل اپلیکیشن
     */
    private fun createDownloadListener(): DownloadListener {
        return DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.isNullOrBlank()) return@DownloadListener

            // در اندروید ۸ و ۹ (API 26 تا 28) نیاز به بررسی مجوز WRITE_EXTERNAL_STORAGE است
            // در اندروید ۱۰ به بالا (API 29+) DownloadManager بدون نیاز به مجوز حساس کار می‌کند
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    pendingDownload = PendingDownloadRequest(
                        url = url,
                        userAgent = userAgent ?: "",
                        contentDisposition = contentDisposition ?: "",
                        mimeType = mimeType ?: "*/*"
                    )
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return@DownloadListener
                }
            }

            enqueueSystemDownload(
                url = url,
                userAgent = userAgent ?: "",
                contentDisposition = contentDisposition ?: "",
                mimeType = mimeType ?: "*/*"
            )
        }
    }

    /**
     * ثبت فایل در DownloadManager اندروید همراه با کوکی‌ها و نمایش در نوار اعلان
     */
    private fun enqueueSystemDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("cookie", cookies)
                }
                if (userAgent.isNotBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                setDescription(getString(R.string.brand_title))
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            // در صورت عدم پشتیبانی لینک توسط DownloadManager، لینک را در مرورگر گوشی باز کن
            Toast.makeText(this, getString(R.string.download_error), Toast.LENGTH_SHORT).show()
            openExternalBrowserOrApp(Uri.parse(url))
        }
    }

    /**
     * تزریق اسکریپت سبک برای تشخیص پخش ویدیوهای HTML5 (حتی در حالت غیر تمام‌صفحه)
     * تا هنگام تماشای فیلم صفحه گوشی خاموش نشود
     */
    private fun injectVideoWakeLockMonitor() {
        val script = """
            (function() {
                if (window.__novaWakeLockAttached) return;
                window.__novaWakeLockAttached = true;
                function checkVideos() {
                    var videos = document.querySelectorAll('video');
                    var anyPlaying = false;
                    for (var i = 0; i < videos.length; i++) {
                        if (!videos[i].paused && !videos[i].ended && videos[i].readyState > 2) {
                            anyPlaying = true;
                            break;
                        }
                    }
                    if (window.NovaWakeLock && window.NovaWakeLock.onVideoStateChanged) {
                        window.NovaWakeLock.onVideoStateChanged(anyPlaying);
                    }
                }
                document.addEventListener('play', checkVideos, true);
                document.addEventListener('playing', checkVideos, true);
                document.addEventListener('pause', checkVideos, true);
                document.addEventListener('ended', checkVideos, true);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script, null)
    }

    private inner class VideoWakeLockBridge {
        @JavascriptInterface
        fun onVideoStateChanged(isPlaying: Boolean) {
            runOnUiThread {
                // اگر در حالت تمام‌صفحه هستیم صفحه همیشه باید روشن بماند؛ در غیر این صورت تابع وضعیت پخش ویدیو باشد
                val shouldKeepAwake = isPlaying || customVideoView != null
                setKeepScreenAwake(shouldKeepAwake)
            }
        }
    }

    /**
     * فعال یا غیرفعال کردن روشن ماندن صفحه (WakeLock)
     */
    private fun setKeepScreenAwake(keepAwake: Boolean) {
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * مخفی‌سازی نوار وضعیت و نوار ناوبری هنگام پخش تمام‌صفحه فیلم
     */
    private fun setImmersiveFullscreenMode(fullscreen: Boolean) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (fullscreen) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * مدیریت هوشمند دکمه بازگشت (Back):
     * ۱. اگر ویدیو تمام‌صفحه است -> خروج از تمام‌صفحه
     * ۲. اگر صفحه قبلی در تاریخچه WebView وجود دارد -> بازگشت به صفحه قبل
     * ۳. در صفحه اول -> با دو بار زدن دکمه بازگشت از برنامه خارج می‌شود
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customVideoView != null -> {
                        binding.webView.webChromeClient?.onHideCustomView()
                    }
                    binding.errorOverlay.visibility == View.VISIBLE && binding.webView.canGoBack() -> {
                        binding.errorOverlay.visibility = View.GONE
                        hasReceivedMainFrameError = false
                        binding.webView.goBack()
                    }
                    binding.webView.canGoBack() -> {
                        binding.webView.goBack()
                    }
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressTime < EXIT_CONFIRM_INTERVAL_MS) {
                            finish()
                        } else {
                            lastBackPressTime = now
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.press_back_again_to_exit),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    private fun loadTargetUrl(url: String) {
        if (isNetworkConnected()) {
            hasReceivedMainFrameError = false
            binding.errorOverlay.visibility = View.GONE
            binding.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            binding.webView.loadUrl(url)
        } else {
            showOfflineErrorOverlay()
        }
    }

    private fun retryLoadingPage() {
        if (isNetworkConnected()) {
            hasReceivedMainFrameError = false
            binding.errorOverlay.visibility = View.GONE
            binding.topProgressBar.visibility = View.VISIBLE
            binding.webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            val target = if (lastFailingUrl.isNotBlank() && lastFailingUrl != "about:blank") {
                lastFailingUrl
            } else {
                BASE_URL
            }
            binding.webView.loadUrl(target)
        } else {
            Toast.makeText(
                this,
                getString(R.string.error_no_internet_title),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showOfflineErrorOverlay() {
        hideSplashSmoothly()
        binding.errorOverlay.visibility = View.VISIBLE
    }

    private fun hideSplashSmoothly() {
        if (binding.splashOverlay.visibility == View.VISIBLE) {
            binding.splashOverlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    binding.splashOverlay.visibility = View.GONE
                    binding.splashOverlay.alpha = 1f
                }
                .start()
        }
    }

    private fun openExternalSchemeSafely(uri: Uri): Boolean {
        return try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun openExternalBrowserOrApp(uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * بررسی وضعیت اتصال به اینترنت (سازگار با اندروید ۸ به بالا)
     */
    private fun isNetworkConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, connectivityCallback)
        } catch (_: Exception) {
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(connectivityCallback)
        } catch (_: Exception) {
        }
        binding.webView.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        super.onDestroy()
    }
}
