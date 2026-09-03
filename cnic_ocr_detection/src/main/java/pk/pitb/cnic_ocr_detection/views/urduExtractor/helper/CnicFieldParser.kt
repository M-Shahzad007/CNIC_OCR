package pk.pitb.cnic_ocr_detection.views.urduExtractor.helper


import android.util.Log

data class CnicFields(
    val name: String?,
    val nameUrdu: String?,
    val fatherName: String?,
    val fatherNameUrdu: String?,
    val gender: String?,          // "M" or "F"
    val countryOfStay: String?,
    val identityNumber: String?,
    val dateOfBirth: String?,
    val dateOfIssue: String?,
    val dateOfExpiry: String?,
    val debugBlocks: Map<String, String> // block label -> raw text used, for troubleshooting
)

/**
 * Parses structured CNIC fields from OCR line results using ANCHOR + BLOCK + REGEX
 * strategy instead of trusting fixed line positions:
 *
 * 1. Find anchor label lines (Name, Father Name, Gender/Country, Identity Number, Issue/Expiry).
 * 2. Slice all lines BETWEEN consecutive anchors into a "block".
 * 3. Within each block, pull the actual value out via regex/keyword matching rather than
 *    assuming a fixed line offset — so it doesn't matter if Gender/Country/M/Pakistan
 *    are on one line, two lines, or in swapped order.
 */
object CnicFieldParser {

    private val DATE_REGEX = Regex("""\b(\d{1,2})[.\-/](\d{1,2})[.\-/](\d{4})\b""")
    private val IDENTITY_REGEX = Regex("""\b\d{5}-\d{7}-\d\b""")
    private val GENDER_REGEX = Regex("""\b(Male|Female|M|F)\b""", RegexOption.IGNORE_CASE)

    private val GENDER_COUNTRY_STOPWORDS =
        setOf("gender", "country", "of", "stay", "male", "female", "m", "f")

    fun parse(result: CardOcrResult): CnicFields {
        val lines = result.lineCrops // already top-to-bottom order, one entry per ML Kit line
        val debug = mutableMapOf<String, String>()

        fun clean(s: String) = s.lowercase().replace(" ", "").replace("\n", "").trim()
        fun textOfRange(start: Int, endExclusive: Int): String =
            if (start < 0) "" else (start until endExclusive.coerceAtMost(lines.size))
                .joinToString(" ") { lines[it].recognizedText }

        // ---- 1. Locate anchor line indices ----
        val nameIdx = lines.indexOfFirst { clean(it.recognizedText) == "name" }
        val fatherIdx = lines.indexOfFirst { clean(it.recognizedText).contains("father") }
        val genderCountryIdx = lines.indexOfFirst {
            val c = clean(it.recognizedText)
            c.contains("gender") || (c.contains("country") && c.contains("stay"))
        }
        val identityIdx = lines.indexOfFirst {
            clean(it.recognizedText).contains("identity")
                    || clean(it.recognizedText).contains("Number")
                    || clean(it.recognizedText).contains("Birth")
        }
        val issueIdx = lines.indexOfFirst { clean(it.recognizedText).contains("issue") }
        val expiryIdx = lines.indexOfFirst {
            val c = clean(it.recognizedText)
            c.contains("expiry") || c.contains("expire")
        }

        // ---- 2. Simple value-next-to-label fields (Name / Father Name) ----
        val name = nameIdx.takeIf { it in 0 until lines.size - 1 }
            ?.let { lines[it + 1].recognizedText.trim() }
        val fatherName = fatherIdx.takeIf { it in 0 until lines.size - 1 }
            ?.let { lines[it + 1].recognizedText.trim() }
        debug["name"] = name.orEmpty()
        debug["fatherName"] = fatherName.orEmpty()

        // ---- 3. Gender / Country block: from genderCountryIdx up to whichever of
        //         Identity / Issue comes next (order on the card can vary) ----
        val genderBlockEnd = listOfNotNull(
            identityIdx.takeIf { it > genderCountryIdx },
            issueIdx.takeIf { it > genderCountryIdx }
        ).minOrNull() ?: lines.size
        val genderBlockText = textOfRange(genderCountryIdx, genderBlockEnd)
        debug["genderCountryBlock"] = genderBlockText

        val gender = GENDER_REGEX.find(genderBlockText)?.value?.let {
            if (it.startsWith("M", ignoreCase = true)) "M" else "F"
        }
        val countryOfStay = genderBlockText
            .split(Regex("\\s+"))
            .map { it.trim() }
            .firstOrNull { w ->
                w.length > 2 && clean(w) !in GENDER_COUNTRY_STOPWORDS && !GENDER_REGEX.matches(
                    w
                )
            }

        // ---- 4. Identity Number / Date of Birth block: from identityIdx up to whichever
        //         of Gender/Country or Issue comes next ----
        val identityBlockEnd = listOfNotNull(
            genderCountryIdx.takeIf { it > identityIdx },
            issueIdx.takeIf { it > identityIdx }
        ).minOrNull() ?: lines.size
        val identityBlockText = textOfRange(identityIdx, identityBlockEnd)
        debug["identityBlock"] = identityBlockText

        val identityNumber = IDENTITY_REGEX.find(identityBlockText)?.value
        val dateOfBirth = DATE_REGEX.find(identityBlockText)?.value

        // ---- 5. Issue / Expiry block: from whichever of issueIdx/expiryIdx comes first,
        //         to end of detected lines — collect ALL dates, smaller = issue, larger = expiry ----
        val datesBlockStart =
            listOfNotNull(issueIdx.takeIf { it >= 0 }, expiryIdx.takeIf { it >= 0 }).minOrNull()
                ?: -1
        val datesBlockText = textOfRange(datesBlockStart, lines.size)
        debug["issueExpiryBlock"] = datesBlockText
//        debug["textData"] = line

        val parsedDates = DATE_REGEX.findAll(datesBlockText)
            .mapNotNull { m ->
                val day = m.groupValues[1].toIntOrNull()
                val month = m.groupValues[2].toIntOrNull()
                val year = m.groupValues[3].toIntOrNull()
                if (day != null && month != null && year != null) {
                    val sortKey = year * 10000 + month * 100 + day
                    Triple(sortKey, "%02d.%02d.%04d".format(day, month, year), m.value)
                } else null
            }
            .distinctBy { it.second }
            .sortedBy { it.first }
            .toList()

        val dateOfIssue = parsedDates.getOrNull(0)?.second
        val dateOfExpiry = parsedDates.getOrNull(1)?.second

        Log.d(
            "CnicFieldParser",
            "name=$name father=$fatherName gender=$gender country=$countryOfStay " +
                    "identity=$identityNumber dob=$dateOfBirth issue=$dateOfIssue expiry=$dateOfExpiry"
        )

        return CnicFields(
            name = name,
            nameUrdu = result.urduNameCrop?.recognizedText,
            fatherName = fatherName,
            fatherNameUrdu = result.urduFatherNameCrop?.recognizedText,
            gender = gender,
            countryOfStay = countryOfStay,
            identityNumber = identityNumber,
            dateOfBirth = dateOfBirth,
            dateOfIssue = dateOfIssue,
            dateOfExpiry = dateOfExpiry,
            debugBlocks = debug
        )
    }
}