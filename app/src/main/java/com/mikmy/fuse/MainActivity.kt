package com.mikmy.fuse

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var view: GameView
    private lateinit var ads: Ads
    private lateinit var privacyButton: TextView

    /** Shows the privacy button on the title screen, and only where consent applies. */
    private val privacyPoll = object : Runnable {
        override fun run() {
            privacyButton.visibility =
                if (view.isOnTitle() && ads.privacyOptionsRequired()) View.VISIBLE else View.GONE
            privacyButton.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Debug-only: "--ez baseline true" runs the same loop drawing only the
        // background, which is how the render cost gets attributed.
        view = GameView(this, intent?.getBooleanExtra("baseline", false) == true)
        ads = Ads(this)
        ads.start()
        view.setOnRunEnded { ads.onRunEnded() }
        val root = FrameLayout(this)
        root.addView(view)
        privacyButton = TextView(this).apply {
            text = "Privacy choices"
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 13f
            val pad = (14 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            setOnClickListener { ads.showPrivacyOptions() }
        }
        root.addView(
            privacyButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START,
            ),
        )
        setContentView(root)
        goFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullscreen()
    }

    override fun onResume() {
        super.onResume()
        view.onResumeGame()
        privacyButton.post(privacyPoll)
    }

    override fun onPause() {
        privacyButton.removeCallbacks(privacyPoll)
        view.onPauseGame()
        super.onPause()
    }

    override fun onBackPressed() {
        // Back on the game screen drops you to the title instead of killing the app.
        if (!view.handleBack()) super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }
}
