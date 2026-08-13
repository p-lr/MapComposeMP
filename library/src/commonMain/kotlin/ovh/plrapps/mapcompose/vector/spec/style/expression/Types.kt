package ovh.plrapps.mapcompose.vector.spec.style.expression

/**
 * The MapLibre expression type lattice.
 *
 * Ported from `maplibre-style-spec/src/expression/types.ts`.
 *
 * Deliberately omitted, because MapCompose has no style properties of these kinds:
 * `projectionDefinition`, `padding`, `numberArray`, `colorArray` and
 * `variableAnchorOffsetCollection`. If such a property is ever added, the type must be added
 * here *and* to [valueMemberTypes], otherwise `checkSubtype(ValueType, ...)` will reject it.
 */
sealed class ExprType {
    abstract val kind: String
}

data object NullType : ExprType() {
    override val kind = "null"
}

data object NumberType : ExprType() {
    override val kind = "number"
}

data object StringType : ExprType() {
    override val kind = "string"
}

data object BooleanType : ExprType() {
    override val kind = "boolean"
}

data object ColorType : ExprType() {
    override val kind = "color"
}

data object ObjectType : ExprType() {
    override val kind = "object"
}

data object ValueType : ExprType() {
    override val kind = "value"
}

data object ErrorType : ExprType() {
    override val kind = "error"
}

data object CollatorType : ExprType() {
    override val kind = "collator"
}

data object FormattedType : ExprType() {
    override val kind = "formatted"
}

data object ResolvedImageType : ExprType() {
    override val kind = "resolvedImage"
}

/** `N == null` means "any length". */
data class ArrayType(val itemType: ExprType, val n: Int? = null) : ExprType() {
    override val kind = "array"
}

fun array(itemType: ExprType, n: Int? = null): ArrayType = ArrayType(itemType, n)

fun typeToString(type: ExprType): String = when (type) {
    is ArrayType -> {
        val itemType = typeToString(type.itemType)
        when {
            type.n != null -> "array<$itemType, ${type.n}>"
            type.itemType == ValueType -> "array"
            else -> "array<$itemType>"
        }
    }

    else -> type.kind
}

private val valueMemberTypes: List<ExprType> = listOf(
    NullType,
    NumberType,
    StringType,
    BooleanType,
    ColorType,
    FormattedType,
    ObjectType,
    array(ValueType),
    ResolvedImageType,
)

/**
 * Returns `null` if [t] is a subtype of [expected]; otherwise an error message.
 */
fun checkSubtype(expected: ExprType, t: ExprType): String? {
    if (t == ErrorType) {
        // Error is a subtype of every type
        return null
    } else if (expected is ArrayType) {
        if (t is ArrayType &&
            ((t.n == 0 && t.itemType == ValueType) || checkSubtype(expected.itemType, t.itemType) == null) &&
            (expected.n == null || expected.n == t.n)
        ) {
            return null
        }
    } else if (expected.kind == t.kind) {
        return null
    } else if (expected == ValueType) {
        for (memberType in valueMemberTypes) {
            if (checkSubtype(memberType, t) == null) {
                return null
            }
        }
    }

    return "Expected ${typeToString(expected)} but found ${typeToString(t)} instead."
}

fun isValidType(provided: ExprType, allowedTypes: List<ExprType>): Boolean =
    allowedTypes.any { it.kind == provided.kind }

/** The JSON-level native types accepted by the `array` assertion. */
enum class NativeType(val typeName: String) {
    NUMBER("number"), STRING("string"), BOOLEAN("boolean"), NULL("null"), ARRAY("array"), OBJECT("object")
}

fun isValidNativeType(provided: Any?, allowedTypes: List<NativeType>): Boolean = allowedTypes.any { t ->
    when (t) {
        NativeType.NULL -> provided == null
        NativeType.ARRAY -> provided is List<*>
        NativeType.OBJECT -> provided is Map<*, *>
        NativeType.NUMBER -> provided is Number
        NativeType.STRING -> provided is String
        NativeType.BOOLEAN -> provided is Boolean
    }
}
