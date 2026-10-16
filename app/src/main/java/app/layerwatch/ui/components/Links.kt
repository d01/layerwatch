package app.layerwatch.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Pages outside the app: the help page on the website, issues on GitHub, the Play listing. */
object Links {
    const val HELP = "https://d01.github.io/layerwatch/help.html"
    const val NEW_ISSUE = "https://github.com/d01/layerwatch/issues/new"

    fun open(context: Context, url: String) {
        view(context, url)
    }

    /** The Play Store app's page for Layerwatch, or the web listing on phones without it. */
    fun openPlayListing(context: Context) {
        val id = context.packageName
        if (!view(context, "market://details?id=$id")) view(context, "https://play.google.com/store/apps/details?id=$id")
    }

    private fun view(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
