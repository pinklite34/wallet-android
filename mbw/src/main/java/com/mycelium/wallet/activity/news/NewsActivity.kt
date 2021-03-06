package com.mycelium.wallet.activity.news

import android.annotation.TargetApi
import android.content.*
import android.content.res.Resources
import android.graphics.Color
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View.*
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.appbar.AppBarLayout
import com.mycelium.wallet.R
import com.mycelium.wallet.activity.modern.NewsFragment
import com.mycelium.wallet.activity.modern.adapter.NewsAdapter
import com.mycelium.wallet.external.mediaflow.GetMediaFlowTopicTask
import com.mycelium.wallet.external.mediaflow.MediaFlowSyncWorker
import com.mycelium.wallet.external.mediaflow.NewsConstants
import com.mycelium.wallet.external.mediaflow.NewsSyncUtils
import com.mycelium.wallet.external.mediaflow.database.NewsDatabase
import com.mycelium.wallet.external.mediaflow.model.News
import kotlinx.android.synthetic.main.activity_news.*
import kotlin.math.abs


class NewsActivity : AppCompatActivity() {
    lateinit var news: News
    private lateinit var preference: SharedPreferences

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NewsConstants.MEDIA_FLOW_UPDATE_ACTION) {
                GetMediaFlowTopicTask(news.id) {
                    it?.let {
                        news = it
                        updateUI()
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)
        collapsing_toolbar.setStatusBarScrimColor(Color.parseColor("#1a1a1a"))
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        app_bar_layout.addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBarLayout, verticalOffset ->
            val scrollDelta = abs(verticalOffset * 1f / appBarLayout.totalScrollRange)
            tvCategory.alpha = 1 - scrollDelta
            toolbar_shadow.visibility = if (scrollDelta == 1f) VISIBLE else GONE
            collapsing_toolbar.title = if (scrollDelta == 1f) Html.fromHtml(news.title) else ""
            llRoot.clipChildren = scrollDelta == 1f
            llRoot.clipToPadding = scrollDelta == 1f
        })
        news = intent.getSerializableExtra(NewsConstants.NEWS) as News
        if (!news.isFull) {
            WorkManager.getInstance(this)
                    .enqueueUniqueWork(NewsSyncUtils.WORK_NAME_ONCE, ExistingWorkPolicy.REPLACE,
                            OneTimeWorkRequest.Builder(MediaFlowSyncWorker::class.java).build())
        }
        NewsDatabase.markRead(news)
        content.setBackgroundColor(Color.TRANSPARENT)
        preference = getSharedPreferences(NewsConstants.NEWS_PREF, Context.MODE_PRIVATE)!!
        updateUI()
        content.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val params = content.layoutParams
                content.measure(0, 0)
                params.height = content.measuredHeight
                content.layoutParams = params
            }

            @TargetApi(Build.VERSION_CODES.N)
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return handleUri(request?.url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUri(Uri.parse(url))
            }

            private fun handleUri(uri: Uri?): Boolean {
                if (uri != null) {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    return true
                }
                return false
            }
        }
        content.imageClicklistener = { url ->
            startActivity(Intent(this, NewsImageActivity::class.java)
                    .putExtra("url", url))
        }
        scrollView.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { v, _, scrollY, _, oldScrollY ->
            val layoutParams = scrollBar.layoutParams
            val scrollHeight = scrollView.getChildAt(0).measuredHeight - scrollView.measuredHeight
            layoutParams.width = scrollView.measuredWidth * scrollY / scrollHeight
            scrollBar.layoutParams = layoutParams
        })
        shareBtn2.setOnClickListener {
            share()
        }
        val fragment = supportFragmentManager.findFragmentById(R.id.otherNews) as NewsFragment
        fragment.currentNews = news
        fragment.newsClick = {
            finish()
            startActivity(Intent(this, NewsActivity::class.java)
                    .putExtra(NewsConstants.NEWS, it))
        }
    }

    fun updateUI() {
        news.content?.let { topicContent ->
            val parsedContent = NewsUtils.parseNews(topicContent)
            val contentText = parsedContent.news
                    .replace("width=\".*?\"", "width=\"100%\"")
                    .replace("width: .*?px", "width: 100%")
                    .replace("height=\".*?\"", "")
            content.settings.defaultFontSize = 14

            val html = getString(R.string.media_flow_html_template
                    , resources.toWebViewPx(12f).toString()
                    , resources.toWebViewPx(24f).toString()
                    , resources.toWebViewPx(16f).toString()
                    , resources.toWebViewPx(2f).toString()
                    , resources.toWebViewPx(8f).toString()
                    , contentText)
            content.loadDataWithBaseURL("https://blog.mycelium.com", html, "text/html", "UTF-8", null)
        }
        news_loading.visibility = if (news.isFull) INVISIBLE else VISIBLE

        tvTitle.text = Html.fromHtml(news.title)
        news.date?.let {
            tvDate.text = NewsUtils.getDateString(this, news)
        }
        tvAuthor.text = news.author?.name

        val categoryText = if (news.categories?.values?.isNotEmpty() == true) news.categories.values.elementAt(0).name else ""
        tvCategory.text = categoryText
        news.image?.let {
            Glide.with(ivImage)
                    .load(news.getFitImage(resources.displayMetrics.widthPixels))
                    .apply(RequestOptions().centerCrop().error(R.drawable.mediaflow_default_picture))
                    .into(ivImage)
        }
    }


    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, IntentFilter(NewsConstants.MEDIA_FLOW_UPDATE_ACTION))
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver)
        super.onPause()
    }

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_SUBJECT, Html.fromHtml(news.title))
                .putExtra(Intent.EXTRA_TEXT, news.link)
                .setType("text/plain"), getString(R.string.share_news)))
    }

    private fun favorite() {
        preference.edit()
                .putBoolean(NewsAdapter.PREF_FAVORITE + news.id, !preference.getBoolean(NewsAdapter.PREF_FAVORITE + news.id, false))
                .apply()
        invalidateOptionsMenu()
    }

    private fun Resources.toWebViewPx(dipValue: Float): Float {
        val metrics = this.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dipValue, metrics) / metrics.density
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_share, menu)
        menu?.findItem(R.id.favorite)?.icon = resources.getDrawable(
                if (preference.getBoolean(NewsAdapter.PREF_FAVORITE + news.id, false)) R.drawable.ic_favorite
                else R.drawable.ic_not_favorite)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.share -> {
                share()
                return true
            }
            R.id.favorite -> {
                favorite()
                return true
            }
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

}
