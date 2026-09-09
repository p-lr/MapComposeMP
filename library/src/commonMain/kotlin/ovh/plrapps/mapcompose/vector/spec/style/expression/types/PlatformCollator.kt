package ovh.plrapps.mapcompose.vector.spec.style.expression.types

/**
 * The locale-dependent half of `["collator", …]`, which only a platform can supply.
 *
 * Upstream is a thin wrapper over `Intl.Collator`
 * (`maplibre-style-spec/src/expression/types/collator.ts`). Kotlin Multiplatform has no ICU of its
 * own, but every target this library builds for reaches the same CLDR collation data through its own
 * API: `Intl.Collator` on wasm, `NSString.compare(_:options:range:locale:)` on iOS and
 * `java.text.Collator` on Android and desktop. So this is an `expect` rather than a hand-rolled fold
 * table, and letter ordering -- Swedish "ä" after "z", not next to "a" -- is the platform's to get
 * right. See [Collator] for what still differs between the three.
 *
 * [sensitivity] is upstream's own value, one of `base`, `accent`, `case` or `variant`; [Collator]
 * derives it from the style's two booleans exactly as `collator.ts` does.
 *
 * An implementation is called once per comparison and holds no state between calls, the rule every
 * file under `spec/style/expression` follows: one `StyleExpression` is shared by the whole
 * tile-worker pool, and `java.text.Collator` is not thread-safe. It is the same choice
 * `formatNumberPlatform` makes for the same reason.
 */
internal expect fun compareLocalized(
    lhs: String,
    rhs: String,
    sensitivity: String,
    locale: String?,
): Int

/**
 * The locale [locale] actually resolves to, as `["resolved-locale", …]` reports it.
 *
 * Upstream is `new Intl.Collator(locale ?: []).resolvedOptions().locale`, i.e. ECMA-402 lookup
 * matching: the requested tag is canonicalized, subtags are dropped from the right until an
 * available locale matches, and the host default answers when none does. Echoing the request back --
 * which this port used to do -- is what made `["resolved-locale", ["collator", {"locale": "dk"}]]`
 * claim a locale no platform has collation data for.
 *
 * With no locale the host's default is returned, which is what upstream does too, so the value
 * differs between platforms exactly as it differs between browsers.
 */
internal expect fun resolveLocalePlatform(locale: String?): String
