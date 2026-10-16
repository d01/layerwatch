package app.layerwatch.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Pages outside the app: the help page on the website, the source and issues on GitHub, the Play listing. */
object Links {
    const val HELP = "https://d01.github.io/layerwatch/help.html"
    const val SOURCE = "https://github.com/d01/layerwatch"
    const val NEW_ISSUE = "$SOURCE/issues/new"

    /** The released app's id, not packageName: debug builds carry a ".debug" suffix that has no listing. */
    private const val PLAY_ID = "app.layerwatch"

    fun open(context: Context, url: String) {
        view(context, url)
    }

    /** The Play Store app's page for Layerwatch, or the web listing on phones without it. */
    fun openPlayListing(context: Context) {
        if (!view(context, "market://details?id=$PLAY_ID")) view(context, "https://play.google.com/store/apps/details?id=$PLAY_ID")
    }

    private fun view(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
