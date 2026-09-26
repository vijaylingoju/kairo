package ai.kairo.gallery.llm

object Prompts {

    val CATEGORIES = listOf(
        "pan_card", "aadhaar_card", "driving_license", "passport",
        "train_ticket", "flight_ticket", "movie_event_ticket",
        "bill_invoice", "payment_receipt", "chat_screenshot",
        "document", "person", "food", "place", "other"
    )

    val FIELD_KEYS = listOf(
        "title", "name", "id_number", "booking_id", "date", "time", "venue", "seats", "amount"
    )

    /** Index-time prompt: image + OCR text -> category, description, tags, fields. */
    fun index(ocrText: String): String = """
You classify ONE gallery image for a search index.
Rules:
- Output exactly one JSON object on a single line. No markdown, no extra text.
- Copy every field value EXACTLY as written in OCR TEXT. If a value is not in OCR TEXT, use null. Never guess or change digits.
- category must be one of: ${CATEGORIES.joinToString(", ")}.
- description: one short sentence a person would type to find this image. For photos without text, name the main subject specifically (animal species or breed, fruit, object, place, scene, colours).
- tags: 5 to 10 lowercase keywords, both specific and general (e.g. "golden retriever", "dog", "pet", "animal").
Example output:
{"category":"train_ticket","description":"IRCTC train ticket from Secunderabad to Rajahmundry","tags":["train","irctc","ticket","travel","secunderabad","rajahmundry"],"fields":{"title":null,"name":"RAVI KUMAR","id_number":null,"booking_id":"4521789630","date":"12-Oct-2026","time":"18:45","venue":"Secunderabad Jn","seats":"S4 32","amount":"845.00"}}
OCR TEXT:
<<<
$ocrText
>>>
"""

    /** Query-time prompt: user question -> search filter. */
    fun query(today: String, userQuery: String): String = """
Convert the user's gallery request into a JSON search filter. Today is $today.
Categories: ${CATEGORIES.joinToString(", ")}.
Output exactly one JSON object on a single line, no markdown:
{"categories":[],"keywords":[],"wanted_field":null,"date_from":null,"date_to":null,"visual":null}
- categories: zero or more from the list above. Leave empty if none clearly fits.
- keywords: important words to match in the image text (names, places, titles). Lowercase.
- wanted_field: null, or one of ${FIELD_KEYS.joinToString(", ")} if the user asks for a specific value.
- date_from / date_to: "yyyy-MM-dd" or null. Only when the user mentions a time period.
- visual: a short English phrase describing what the photo looks like, or null for pure document/value questions.
Example: "what's my PAN number" -> {"categories":["pan_card"],"keywords":[],"wanted_field":"id_number","date_from":null,"date_to":null,"visual":null}
Example: "sunset at the lake with my dog" -> {"categories":[],"keywords":["lake"],"wanted_field":null,"date_from":null,"date_to":null,"visual":"a dog at a lake during sunset"}
User: $userQuery
"""
}
