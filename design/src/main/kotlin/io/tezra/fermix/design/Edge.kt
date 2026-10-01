package io.tezra.fermix.design

/** Where a [controlPlane] surface meets content, which is where its hairline is drawn. */
enum class Edge {
    /** A bar above content: the app bar. */
    Bottom,

    /** A bar below content, such as search's step bar. */
    Top,

    /** The floating composer dock, which content passes on every side: a 28 dp pill outlined all round. */
    Around,
}
