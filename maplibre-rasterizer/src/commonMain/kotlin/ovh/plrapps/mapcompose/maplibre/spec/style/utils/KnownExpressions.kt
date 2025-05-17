package ovh.plrapps.mapcompose.maplibre.spec.style.utils


internal val knownExpressions = setOf(
    // 🧮 Arithmetic
    "+", "-", "*", "/", "%", "^",

    // 🧠 Type & Conversion
    "typeof", "to-number", "to-string", "to-boolean", "to-color", "number-format",

    // 🎨 Color
    "rgb", "rgba", "to-rgba",

    // 🔢 Math
    "abs", "acos", "asin", "atan", "ceil", "cos", "e", "floor", "ln", "ln2",
    "log10", "log2", "max", "min", "pi", "round", "sin", "sqrt", "tan",

    // 🔗 Logical
    "!", "==", "!=", ">", ">=", "<", "<=", "all", "any",

    // 🔀 Control Flow
    "case", "match", "coalesce", "step", "interpolate", "interpolate-hcl", "interpolate-lab",

    // 🧰 Data Manipulation
    "get", "has", "in", "at", "index-of", "length", "slice",

    // 🧾 Feature
    "feature-state", "geometry-type", "id", "properties",

    // 📷 Camera
    "zoom", "line-progress",

    // 📦 Constants
    "literal",

    // 🔡 String
    "concat", "downcase", "upcase", "is-supported-script", "resolved-locale",

    // 🧠 Structures
    "array", "boolean", "collator", "format", "image", "number", "object", "string",

    // 🧪 Variables
    "let", "var"
)