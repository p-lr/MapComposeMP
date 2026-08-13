package ovh.plrapps.mapcompose.vector.spec.style.expression.types

/**
 * Ported from `maplibre-style-spec/src/expression/types/collator.ts`.
 *
 * Divergence: Kotlin Multiplatform has no ICU, so there is no `Intl.Collator` equivalent. This
 * implementation is locale-agnostic:
 * - case-insensitive comparison uses [String.lowercase]
 * - diacritic-insensitive comparison strips combining marks from the Unicode range U+0300..U+036F
 *   after NFD-like decomposition of the Latin-1 range (a lookup table, not full NFD)
 * - [resolvedLocale] echoes back the requested locale, or "en" when none was given
 */
class Collator(
    private val caseSensitive: Boolean,
    private val diacriticSensitive: Boolean,
    val locale: String?,
) {
    val sensitivity: String = if (caseSensitive) {
        if (diacriticSensitive) "variant" else "case"
    } else {
        if (diacriticSensitive) "accent" else "base"
    }

    /**
     * Compares at up to three ICU-style strength levels.
     *
     * - **Primary** always: diacritics stripped and case folded, so "\u00e4" sorts next to "a" and
     *   before "b" rather than after "z" as raw code-point order would have it.
     * - **Secondary** only when [diacriticSensitive]: accented forms sort after their plain base.
     * - **Tertiary** only when [caseSensitive]: lowercase sorts before uppercase, as ICU does.
     *
     * Locale-tailored rules (German "\u00fc" == "ue", Swedish "\u00e4" after "z") are out of reach without ICU;
     * see the class KDoc.
     */
    fun compare(lhs: String, rhs: String): Int {
        val primary = fold(lhs).compareTo(fold(rhs))
        if (primary != 0) return primary

        if (diacriticSensitive) {
            val secondary = lhs.lowercase().compareTo(rhs.lowercase())
            if (secondary != 0) return secondary
        }

        if (caseSensitive) {
            val tertiary = compareCase(lhs, rhs)
            if (tertiary != 0) return tertiary
        }

        return 0
    }

    /** The primary key: diacritics stripped and case folded, regardless of sensitivity. */
    private fun fold(s: String): String = stripDiacritics(s).lowercase()

    /** ICU orders lowercase before uppercase at the tertiary level; raw code points do the reverse. */
    private fun compareCase(lhs: String, rhs: String): Int {
        val n = minOf(lhs.length, rhs.length)
        for (i in 0 until n) {
            val a = if (lhs[i].isUpperCase()) 1 else 0
            val b = if (rhs[i].isUpperCase()) 1 else 0
            if (a != b) return a - b
        }
        return lhs.length - rhs.length
    }

    fun resolvedLocale(): String = locale ?: "en"

    override fun equals(other: Any?): Boolean = other is Collator &&
            other.caseSensitive == caseSensitive &&
            other.diacriticSensitive == diacriticSensitive &&
            other.locale == locale

    override fun hashCode(): Int {
        var result = caseSensitive.hashCode()
        result = 31 * result + diacriticSensitive.hashCode()
        result = 31 * result + (locale?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "collator($sensitivity, ${locale ?: ""})"

    private companion object {
        /**
         * Latin-1 Supplement, Latin Extended-A/B and Latin Extended Additional folded to their
         * unaccented ASCII base letter. Generated from Unicode NFD decomposition; characters
         * outside these blocks are left untouched, as are decompositions that are not a single
         * ASCII letter (\u00df, \u00e6, \u0111 and friends).
         */
        private const val ACCENTED =
                    "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u00d9\u00da\u00db\u00dc\u00dd\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5" +
                    "\u00e7\u00e8\u00e9\u00ea\u00eb\u00ec\u00ed\u00ee\u00ef\u00f1\u00f2\u00f3\u00f4\u00f5\u00f6\u00f9\u00fa\u00fb\u00fc\u00fd\u00ff\u0100\u0101\u0102\u0103\u0104\u0105\u0106\u0107\u0108\u0109\u010a" +
                    "\u010b\u010c\u010d\u010e\u010f\u0112\u0113\u0114\u0115\u0116\u0117\u0118\u0119\u011a\u011b\u011c\u011d\u011e\u011f\u0120\u0121\u0122\u0123\u0124\u0125\u0128\u0129\u012a\u012b\u012c\u012d\u012e" +
                    "\u012f\u0130\u0134\u0135\u0136\u0137\u0139\u013a\u013b\u013c\u013d\u013e\u0143\u0144\u0145\u0146\u0147\u0148\u014c\u014d\u014e\u014f\u0150\u0151\u0154\u0155\u0156\u0157\u0158\u0159\u015a\u015b" +
                    "\u015c\u015d\u015e\u015f\u0160\u0161\u0162\u0163\u0164\u0165\u0168\u0169\u016a\u016b\u016c\u016d\u016e\u016f\u0170\u0171\u0172\u0173\u0174\u0175\u0176\u0177\u0178\u0179\u017a\u017b\u017c\u017d" +
                    "\u017e\u01a0\u01a1\u01af\u01b0\u01cd\u01ce\u01cf\u01d0\u01d1\u01d2\u01d3\u01d4\u01d5\u01d6\u01d7\u01d8\u01d9\u01da\u01db\u01dc\u01de\u01df\u01e0\u01e1\u01e6\u01e7\u01e8\u01e9\u01ea\u01eb\u01ec" +
                    "\u01ed\u01f0\u01f4\u01f5\u01f8\u01f9\u01fa\u01fb\u0200\u0201\u0202\u0203\u0204\u0205\u0206\u0207\u0208\u0209\u020a\u020b\u020c\u020d\u020e\u020f\u0210\u0211\u0212\u0213\u0214\u0215\u0216\u0217" +
                    "\u0218\u0219\u021a\u021b\u021e\u021f\u0226\u0227\u0228\u0229\u022a\u022b\u022c\u022d\u022e\u022f\u0230\u0231\u0232\u0233\u1e00\u1e01\u1e02\u1e03\u1e04\u1e05\u1e06\u1e07\u1e08\u1e09\u1e0a\u1e0b" +
                    "\u1e0c\u1e0d\u1e0e\u1e0f\u1e10\u1e11\u1e12\u1e13\u1e14\u1e15\u1e16\u1e17\u1e18\u1e19\u1e1a\u1e1b\u1e1c\u1e1d\u1e1e\u1e1f\u1e20\u1e21\u1e22\u1e23\u1e24\u1e25\u1e26\u1e27\u1e28\u1e29\u1e2a\u1e2b" +
                    "\u1e2c\u1e2d\u1e2e\u1e2f\u1e30\u1e31\u1e32\u1e33\u1e34\u1e35\u1e36\u1e37\u1e38\u1e39\u1e3a\u1e3b\u1e3c\u1e3d\u1e3e\u1e3f\u1e40\u1e41\u1e42\u1e43\u1e44\u1e45\u1e46\u1e47\u1e48\u1e49\u1e4a\u1e4b" +
                    "\u1e4c\u1e4d\u1e4e\u1e4f\u1e50\u1e51\u1e52\u1e53\u1e54\u1e55\u1e56\u1e57\u1e58\u1e59\u1e5a\u1e5b\u1e5c\u1e5d\u1e5e\u1e5f\u1e60\u1e61\u1e62\u1e63\u1e64\u1e65\u1e66\u1e67\u1e68\u1e69\u1e6a\u1e6b" +
                    "\u1e6c\u1e6d\u1e6e\u1e6f\u1e70\u1e71\u1e72\u1e73\u1e74\u1e75\u1e76\u1e77\u1e78\u1e79\u1e7a\u1e7b\u1e7c\u1e7d\u1e7e\u1e7f\u1e80\u1e81\u1e82\u1e83\u1e84\u1e85\u1e86\u1e87\u1e88\u1e89\u1e8a\u1e8b" +
                    "\u1e8c\u1e8d\u1e8e\u1e8f\u1e90\u1e91\u1e92\u1e93\u1e94\u1e95\u1e96\u1e97\u1e98\u1e99\u1ea0\u1ea1\u1ea2\u1ea3\u1ea4\u1ea5\u1ea6\u1ea7\u1ea8\u1ea9\u1eaa\u1eab\u1eac\u1ead\u1eae\u1eaf\u1eb0\u1eb1" +
                    "\u1eb2\u1eb3\u1eb4\u1eb5\u1eb6\u1eb7\u1eb8\u1eb9\u1eba\u1ebb\u1ebc\u1ebd\u1ebe\u1ebf\u1ec0\u1ec1\u1ec2\u1ec3\u1ec4\u1ec5\u1ec6\u1ec7\u1ec8\u1ec9\u1eca\u1ecb\u1ecc\u1ecd\u1ece\u1ecf\u1ed0\u1ed1" +
                    "\u1ed2\u1ed3\u1ed4\u1ed5\u1ed6\u1ed7\u1ed8\u1ed9\u1eda\u1edb\u1edc\u1edd\u1ede\u1edf\u1ee0\u1ee1\u1ee2\u1ee3\u1ee4\u1ee5\u1ee6\u1ee7\u1ee8\u1ee9\u1eea\u1eeb\u1eec\u1eed\u1eee\u1eef\u1ef0\u1ef1" +
                    "\u1ef2\u1ef3\u1ef4\u1ef5\u1ef6\u1ef7\u1ef8\u1ef9"

        private const val PLAIN =
                    "AAAAAACEEEEIIIINOOOOOUUUUYaaaaaa" +
                    "ceeeeiiiinooooouuuuyyAaAaAaCcCcC" +
                    "cCcDdEeEeEeEeEeGgGgGgGgHhIiIiIiI" +
                    "iIJjKkLlLlLlNnNnNnOoOoOoRrRrRrSs" +
                    "SsSsSsTtTtUuUuUuUuUuUuWwYyYZzZzZ" +
                    "zOoUuAaIiOoUuUuUuUuUuAaAaGgKkOoO" +
                    "ojGgNnAaAaAaEeEeIiIiOoOoRrRrUuUu" +
                    "SsTtHhAaEeOoOoOoOoYyAaBbBbBbCcDd" +
                    "DdDdDdDdEeEeEeEeEeFfGgHhHhHhHhHh" +
                    "IiIiKkKkKkLlLlLlLlMmMmMmNnNnNnNn" +
                    "OoOoOoOoPpPpRrRrRrRrSsSsSsSsSsTt" +
                    "TtTtTtUuUuUuUuUuVvVvWwWwWwWwWwXx" +
                    "XxYyZzZzZzhtwyAaAaAaAaAaAaAaAaAa" +
                    "AaAaAaEeEeEeEeEeEeEeEeIiIiOoOoOo" +
                    "OoOoOoOoOoOoOoOoOoUuUuUuUuUuUuUu" +
                    "YyYyYyYy"

        fun stripDiacritics(s: String): String {
            if (s.all { it.code < 0x00C0 }) return s
            val sb = StringBuilder(s.length)
            for (ch in s) {
                if (ch.code in 0x0300..0x036F) continue // combining marks
                val idx = ACCENTED.indexOf(ch)
                sb.append(if (idx >= 0) PLAIN[idx] else ch)
            }
            return sb.toString()
        }
    }
}
