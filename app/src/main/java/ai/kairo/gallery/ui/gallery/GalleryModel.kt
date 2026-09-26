package ai.kairo.gallery.ui.gallery

import ai.kairo.gallery.data.IndexedImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Photos grouped under one day header ("Today", "Yesterday", "Sat, 26 Sep"). */
data class DayGroup(val label: String, val photos: List<IndexedImage>)

fun groupByDay(photos: List<IndexedImage>, now: Long = System.currentTimeMillis()): List<DayGroup> {
    val cal = Calendar.getInstance()
    fun dayKey(ms: Long): Int {
        cal.timeInMillis = ms
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }
    val today = dayKey(now)
    val yesterday = dayKey(now - 24L * 3600 * 1000)
    cal.timeInMillis = now
    val thisYear = cal.get(Calendar.YEAR)
    val sameYear = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    val otherYear = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return photos.sortedByDescending { it.dateTaken }
        .groupBy { dayKey(it.dateTaken) }
        .map { (key, list) ->
            val label = when (key) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> if (key / 1000 == thisYear) sameYear.format(list.first().dateTaken) else otherYear.format(list.first().dateTaken)
            }
            DayGroup(label, list)
        }
}

fun shortDate(ms: Long): String = SimpleDateFormat("d MMM yyyy · h:mm a", Locale.getDefault()).format(ms)

/** Albums Kairo builds automatically from what it understood in each photo. */
data class SmartAlbum(
    val key: String,
    val title: String,
    val private: Boolean = false,   // cover is blurred, content is sensitive
    val matches: (IndexedImage) -> Boolean,
)

private val TICKETS = setOf("train_ticket", "bus_ticket", "flight_ticket", "movie_event_ticket")
private val IDS = setOf("pan_card", "aadhaar_card", "driving_license", "passport", "document")
private val MONEY = setOf("bill_invoice", "payment_receipt")

fun isScreenshot(p: IndexedImage) = p.name.startsWith("Screenshot", ignoreCase = true) || "Screenshots" in p.folder

val SMART_ALBUMS = listOf(
    SmartAlbum("all", "All photos") { true },
    SmartAlbum("tickets", "Tickets") { it.category in TICKETS },
    SmartAlbum("ids", "IDs & documents", private = true) { it.category in IDS },
    SmartAlbum("money", "Bills & payments") { it.category in MONEY },
    SmartAlbum("food", "Food") { it.category == "food" },
    SmartAlbum("people", "People") { it.category == "person" },
    SmartAlbum("places", "Places") { it.category == "place" || it.lat != null },
    SmartAlbum("screenshots", "Screenshots") { isScreenshot(it) },
)

fun prettyCategory(c: String): String = when (c) {
    "pan_card" -> "PAN card"
    "aadhaar_card" -> "Aadhaar"
    "driving_license" -> "Driving licence"
    "passport" -> "Passport"
    "train_ticket" -> "Train ticket"
    "bus_ticket" -> "Bus ticket"
    "flight_ticket" -> "Flight ticket"
    "movie_event_ticket" -> "Movie ticket"
    "bill_invoice" -> "Bill"
    "payment_receipt" -> "Payment"
    "chat_screenshot" -> "Screenshot"
    "document" -> "Document"
    "person" -> "People"
    "food" -> "Food"
    "place" -> "Place"
    else -> c.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun prettyField(k: String, category: String): String = when (k) {
    "id_number" -> when (category) {
        "pan_card" -> "PAN number"
        "aadhaar_card" -> "Aadhaar number"
        "driving_license" -> "Licence number"
        "passport" -> "Passport number"
        else -> "ID number"
    }
    "booking_id" -> when (category) {
        "train_ticket", "bus_ticket", "flight_ticket" -> "PNR"
        "payment_receipt" -> "Transaction ID"
        "bill_invoice" -> "Bill no."
        else -> "Booking ID"
    }
    "title" -> "Title"
    "name" -> "Name"
    "date" -> "Date"
    "time" -> "Time"
    "venue" -> "Venue"
    "seats" -> "Seats"
    "amount" -> "Amount"
    else -> k
}

/** ID numbers are shown as •••• 1234 until the user taps to reveal them. */
fun maskId(value: String): String {
    val tail = value.filter { it.isLetterOrDigit() }.takeLast(4)
    return "•••• •••• $tail"
}

val SEARCH_SUGGESTIONS = listOf(
    "My PAN number",
    "Movie tickets",
    "What's my PNR",
    "Food photos",
    "Bills this month",
    "Ice cream",
    "సినిమా టికెట్లు",
    "Seat number for my flight",
)
