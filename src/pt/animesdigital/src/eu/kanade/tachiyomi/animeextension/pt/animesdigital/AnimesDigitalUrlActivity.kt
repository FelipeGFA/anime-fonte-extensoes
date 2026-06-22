package eu.kanade.tachiyomi.animeextension.pt.animesdigital

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class AnimesDigitalUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mainIntent = Intent().apply {
            action = "eu.kanade.tachiyomi.ANIMESEARCH"
            putExtra("query", intent.data.toString())
            putExtra("filter", packageName)
        }

        try {
            startActivity(mainIntent)
        } catch (e: Throwable) {
            Log.e(tag, "Unable to launch activity", e)
        }

        finish()
        exitProcess(0)
    }
}
