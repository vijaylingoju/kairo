package ai.kairo.gallery.llm

object Prompts {

    val CATEGORIES = listOf(
        "pan_card", "aadhaar_card", "driving_license", "passport",
        "train_ticket", "bus_ticket", "flight_ticket", "movie_event_ticket",
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

    /**
     * Compact prompt for photos with (almost) no text. Fields are left out on purpose: every field value must
     * appear in the OCR text or it is discarded, so on a text-less photo they would all be thrown away anyway.
     */
    fun indexPhoto(ocrText: String): String = """
You label ONE gallery photo for a search index.
Output exactly one JSON object on a single line. No markdown, no extra text.
- category must be one of: ${CATEGORIES.joinToString(", ")}.
- description: one short sentence a person would type to find this photo. Name the main subject specifically (animal species or breed, food, object, place, scene, colours).
- tags: 5 to 10 lowercase keywords, both specific and general (e.g. "golden retriever", "dog", "pet", "animal").
Example output:
{"category":"food","description":"A plate of masala dosa with coconut chutney and sambar","tags":["masala dosa","dosa","south indian","breakfast","chutney","sambar","food"]}
Text visible in the photo (may be empty or noise): "${ocrText.replace('\n', ' ').take(200)}"
"""

    /**
     * Document prompt: same fields as [index], but only the ones actually present are written (no `null`s),
     * which shortens the answer without losing anything.
     */
    fun indexDocument(ocrText: String): String = """
You classify ONE gallery image for a search index.
Rules:
- Output exactly one JSON object on a single line. No markdown, no extra text.
- category must be one of: ${CATEGORIES.joinToString(", ")}.
- description: one short sentence a person would type to find this image.
- tags: 5 to 10 lowercase keywords, both specific and general.
- fields: include ONLY keys whose value is written in OCR TEXT, copied EXACTLY (never guess or change digits). Leave out every other key. Allowed keys: ${FIELD_KEYS.joinToString(", ")}.
Example output:
{"category":"train_ticket","description":"IRCTC train ticket from Secunderabad to Rajahmundry","tags":["train","irctc","ticket","travel","secunderabad","rajahmundry"],"fields":{"name":"RAVI KUMAR","booking_id":"4521789630","date":"12-Oct-2026","time":"18:45","venue":"Secunderabad Jn","seats":"S4 32","amount":"845.00"}}
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
- The user may write in any language (English, Telugu, Hindi...) and may misspell words. Always answer in English, with spelling fixed and slang turned into plain dictionary words (doggo -> dog, pic -> photo).
- keywords: important English words to match in the image text or description (names, places, titles, objects). Lowercase.
- wanted_field: null, or one of ${FIELD_KEYS.joinToString(", ")} if the user asks for a specific value.
- date_from / date_to: "yyyy-MM-dd" or null. Only when the user mentions a time period.
- visual: REQUIRED whenever the request is about what a photo shows (objects, animals, people, food, places, scenes): a short English phrase describing the photo. null only for document/value questions.
Example: "what's my PAN number" -> {"categories":["pan_card"],"keywords":[],"wanted_field":"id_number","date_from":null,"date_to":null,"visual":null}
Example: "sunset at the lake with my dog" -> {"categories":[],"keywords":["lake"],"wanted_field":null,"date_from":null,"date_to":null,"visual":"a dog at a lake during sunset"}
Example: "icecream" -> {"categories":[],"keywords":["ice","cream"],"wanted_field":null,"date_from":null,"date_to":null,"visual":"an ice cream cone"}
Example: "कुत्ता" -> {"categories":[],"keywords":["dog"],"wanted_field":null,"date_from":null,"date_to":null,"visual":"a dog"}
User: $userQuery
"""
}
