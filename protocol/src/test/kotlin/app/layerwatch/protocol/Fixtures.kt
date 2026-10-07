package app.layerwatch.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Raw report payloads recorded from an X1C mid-print (identifiers replaced with placeholders). */
object Fixtures {
    const val SERIAL = "00M00A000000000"

    val captureReports: List<String> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/capture-20261007-0751.jsonl"))
        stream.bufferedReader().readLines().filter { it.isNotBlank() }.map {
            Json.parseToJsonElement(it).jsonObject.getValue("payload").toString()
        }
    }
}
