package ovh.plrapps.mapcompose.vector.spec.style.expression

/**
 * Tracks `let` bindings during expression parsing.
 *
 * Ported from `maplibre-style-spec/src/expression/scope.ts`. Note that `var` resolution happens at
 * *parse* time: [Var] looks the name up here and holds a direct reference to the bound expression,
 * so there is no binding map at evaluation time.
 */
class Scope(
    private val parent: Scope? = null,
    bindings: List<Pair<String, Expression>> = emptyList(),
) {
    private val bindings: Map<String, Expression> = bindings.toMap()

    fun concat(bindings: List<Pair<String, Expression>>): Scope = Scope(this, bindings)

    fun get(name: String): Expression =
        bindings[name] ?: parent?.get(name) ?: error("$name not found in scope.")

    fun has(name: String): Boolean = bindings.containsKey(name) || (parent?.has(name) ?: false)
}
