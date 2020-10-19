package com.draco.libretrowrapper.activities

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.draco.libretrowrapper.R
import com.draco.libretrowrapper.fragments.GamePadFragment
import com.draco.libretrowrapper.fragments.RetroViewFragment
import com.draco.libretrowrapper.utils.CoreUpdater
import com.draco.libretrowrapper.utils.Input
import com.draco.libretrowrapper.utils.PrivateData
import java.io.File
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch

class GameActivity : AppCompatActivity() {
    /* Essential objects */
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var privateData: PrivateData
    private lateinit var coreUpdater: CoreUpdater

    /* Fragments */
    private val retroViewFragment = RetroViewFragment()
    private val gamePadFragment = GamePadFragment()

    /* Input handler for GLRetroView */
    private val input = Input()

    /* Latch that waits until the activity is focused before continuing */
    private var canCommitFragmentsLatch = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        /* Setup essential objects */
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        privateData = PrivateData(this)
        coreUpdater = CoreUpdater(this)

        /* Make sure we reapply immersive mode on rotate */
        window.decorView.setOnApplyWindowInsetsListener { _, windowInsets ->
            immersive()
            return@setOnApplyWindowInsetsListener windowInsets
        }

        /*
         * We have a progress spinner on the screen at this point until the GLRetroView
         * renders a frame. Let's setup our ROM, core, and GLRetroView in a background thread.
         */
        Thread {
            /* Setup ROM and core if we haven't already */
            try {
                initAssets()
            } catch (_: UnknownHostException) {
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.fetch_error_dialog_title))
                        .setMessage(getString(R.string.fetch_error_dialog_message))
                        .setPositiveButton(getString(R.string.fetch_error_dialog_exit)) { _, _ -> finishAffinity() }
                        .setCancelable(false)
                        .show()
                }
                return@Thread
            }

            /*
             * It is unsafe to commit fragments after onSaveInstanceState is called. We MUST
             * wait until the activity resumes focus before continuing.
             */
            canCommitFragmentsLatch.await()

            /* Add the GLRetroView to main layout now that the assets are prepared */
            runOnUiThread {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.retroview_container, retroViewFragment)
                    .runOnCommit { canCommitFragmentsLatch = CountDownLatch(1) }
                    .commitNow()
            }

            /*
             * The GLRetroView will take a while to load up the ROM and core, so before we
             * finish up, we should wait for the GLRetroView to become usable.
             */
            retroViewFragment.retroViewReadyLatch.await()

            /*
             * If we started this activity after a configuration change, restore the temp state.
             * It is not reliable to handle this in the fragment since the fragment is recreated
             * on a configuration change, meaning that the savedInstanceState will always report
             * null, making it impossible to differentiate a cold start from a warm start. Handle
             * the configurations in the parent activity.
             */
            if (savedInstanceState != null)
                retroViewFragment.restoreTempState()

            /*
             * The fragment will subscribe the GLRetroView on start, prepare it.
             * It is also guaranteed that the GLRetroView is prepared in the Fragment class.
             */
            gamePadFragment.retroView = retroViewFragment.retroView!!

            /*
             * If we initialize the GamePads too early, the user could load a state before the
             * emulator is ready, causing a crash. We MUST wait for the GLRetroView to render
             * a frame first.
             */
            runOnUiThread {
                supportFragmentManager
                    .beginTransaction()
                    .replace(R.id.containers, gamePadFragment)
                    .commitNow()
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        /* Android is about to kill the activity; save a temporary state snapshot */
        retroViewFragment.saveTempState()
    }

    private fun initAssets() {
        /* Copy over our assets from the internal assets directory */
        for (asset in assets.list("")!!) {
            val assetFile = File("${filesDir.absolutePath}/$asset")
            if (!assetFile.exists()) try {
                val assetInputStream = assets.open(asset)
                val assetOutputStream = assetFile.outputStream()

                assetInputStream.copyTo(assetOutputStream)

                assetOutputStream.close()
                assetInputStream.close()
            } catch (_: Exception) {}
        }

        /* Update the core from the internet if possible */
        if (!privateData.core.exists())
            coreUpdater.update()
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            with (window.insetsController!!) {
                hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars()
                )
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (!hasFocus)
            return

        /* Reapply our immersive mode again */
        immersive()

        /* Let waiting threads know that it is now safe to commit fragments */
        canCommitFragmentsLatch.countDown()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return input.handleKeyEvent(retroViewFragment.retroView, keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return input.handleKeyEvent(retroViewFragment.retroView, keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (input.handleGenericMotionEvent(retroViewFragment.retroView, event))
            return true

        return super.onGenericMotionEvent(event)
    }
}