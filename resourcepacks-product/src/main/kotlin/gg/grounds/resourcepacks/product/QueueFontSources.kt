package gg.grounds.resourcepacks.product

/** Source-pinned native 26.3 glyph subsets. See queue-source/NOTICE.txt. */
internal object QueueFontSources {
    data class Atlas(
        val file: String,
        val height: Int,
        val ascent: Int,
        val chars: List<String>,
        val sha256: String,
    )

    val atlases =
        listOf(
            Atlas(
                "latin_0.png",
                8,
                7,
                listOf(
                    "\u00a1\u00ad\u00b7\u00bf\u00d7\u00d8\u00de\u00f0\u00f8\u00fe\u2013\u2014\u2026\u00df\u00b9\u00b3",
                    "\u00a2\u00a4\u00a5\u00a9\u00ae\u00b5\u00b6\u00bc\u00bd\u00be\u00af\u00a6\u00b4\u00a8\u00a7\u0000",
                ),
                "c68ff4551d67bdf7adc1146163ee4f37cc8bc7eccfb57d5e2276bec21a8614ab",
            ),
            Atlas(
                "latin_1.png",
                12,
                10,
                listOf(
                    "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00c6\u00c7\u00c8\u00c9\u00ca\u00cb\u00cc\u00cd\u00ce\u00cf",
                    "\u00d0\u00d1\u00d2\u00d3\u00d4\u00d5\u00d6\u00d9\u00da\u00db\u00dc\u00dd\u00e0\u00e1\u00e2\u00e3",
                    "\u00e4\u00e5\u00e6\u00e7\u00ec\u00ed\u00ee\u00ef\u00f1\u00f2\u00f3\u00f4\u00f5\u00f6\u00f9\u00fa",
                    "\u00fb\u00fc\u00fd\u00ff\u00e8\u00e9\u00ea\u00eb\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000",
                ),
                "807417fb52f651b9ce8fd388e5a2865d8bd4bd4aa1ec07c53c6603ddae981355",
            ),
            Atlas(
                "latin_2.png",
                8,
                7,
                listOf(
                    " !\"#\$%&'()*+,-./",
                    "0123456789:;<=>?",
                    "@ABCDEFGHIJKLMNO",
                    "PQRSTUVWXYZ[\\]^_",
                    "`abcdefghijklmno",
                    "pqrstuvwxyz{|}~\u00a3",
                    "\u00aa\u00ba\u00ac\u00ab\u00bb\u00b1\u00f7\u00b0\u00b2\u0000\u0000\u0000\u0000\u0000\u0000\u0000",
                ),
                "61fb5bb8d27ef0f229e837643d7db30542ec7549c7bace25a3e633a6c091c1f6",
            ),
        )
}
