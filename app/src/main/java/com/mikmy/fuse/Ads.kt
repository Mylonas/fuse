package com.mikmy.fuse

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Interstitial ads on game over, with the frequency capping that keeps a
 * one-more-go game playable.
 *
 * Every call is wrapped: no ad failure, missing consent or absent Play
 * Services may ever affect the game. If ads do not work, the game simply has
 * no ads.
 *
 * Ad unit IDs come from BuildConfig, which defaults to Google's official test
 * IDs. Debug builds are pinned to the test IDs regardless — serving yourself
 * live ads is an AdMob policy violation that gets accounts suspended.
 */
class Ads(private val activity: Activity) {

    companion object {
        private const val TAG = "Ads"

        /** Never interrupt the first few runs; that is when a player decides. */
        private const val RUNS_BEFORE_FIRST_AD = 3

        /** Then at most one ad per this many runs... */
        private const val RUNS_BETWEEN_ADS = 3

        /** ...and never more often than this, however short the runs are. */
        private const val SECONDS_BETWEEN_ADS = 90L
    }

    private val adUnitId = BuildConfig.ADMOB_INTERSTITIAL_ID

    private var initialised = false
    private var runs = 0
    private var runsSinceAd = 0
    private var lastAdAt = 0L
    private var showing = false

    /** Call once from onCreate. Gathers consent, then initialises and preloads. */
    fun start() {
        try {
            val consent = UserMessagingPlatform.getConsentInformation(activity)
            val params = ConsentRequestParameters.Builder().build()
            consent.requestConsentInfoUpdate(
                activity,
                params,
                {
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        if (formError != null) Log.w(TAG, "consent form: ${formError.message}")
                        if (consent.canRequestAds()) initialise()
                    }
                },
                { requestError ->
                    // No consent info (offline, outside the EEA, whatever).
                    // Initialise anyway; the SDK serves non-personalised ads.
                    Log.w(TAG, "consent update: ${requestError.message}")
                    initialise()
                }
            )
        } catch (e: Throwable) {
            Log.w(TAG, "consent setup failed, continuing without ads", e)
        }
    }

    private fun initialise() {
        if (initialised) return
        initialised = true
        // Initialisation does disk and network work. On the main thread it
        // ANRs, which Google's own docs call out.
        Thread {
            try {
                MobileAds.initialize(
                    activity,
                    InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build()
                ) {
                    preload()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ads init failed, continuing without ads", e)
            }
        }.apply { isDaemon = true; name = "ads-init" }.start()
    }

    private fun preload() {
        try {
            val request = AdRequest.Builder(adUnitId).build()
            InterstitialAdPreloader.start(adUnitId, PreloadConfiguration(request))
        } catch (e: Throwable) {
            Log.w(TAG, "preload failed", e)
        }
    }

    /** Called on the main thread when a run ends. */
    fun onRunEnded() {
        runs++
        runsSinceAd++
        if (!shouldShow()) return
        try {
            val ad = InterstitialAdPreloader.pollAd(adUnitId)
            if (ad == null) {
                Log.d(TAG, "no ad ready; skipping")
                return
            }
            ad.adEventCallback = object : InterstitialAdEventCallback {
                override fun onAdDismissedFullScreenContent() {
                    showing = false
                }
            }
            showing = true
            lastAdAt = SystemClock.elapsedRealtime()
            runsSinceAd = 0
            ad.show(activity)
        } catch (e: Throwable) {
            showing = false
            Log.w(TAG, "show failed", e)
        }
    }

    private fun shouldShow(): Boolean {
        if (showing) return false
        if (runs <= RUNS_BEFORE_FIRST_AD) return false
        if (runsSinceAd < RUNS_BETWEEN_ADS) return false
        if (lastAdAt == 0L) return true
        return (SystemClock.elapsedRealtime() - lastAdAt) / 1000L >= SECONDS_BETWEEN_ADS
    }
}
