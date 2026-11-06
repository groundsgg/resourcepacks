package gg.grounds.resourcepacks.product

import gg.grounds.resourcepack.api.PackEntry
import gg.grounds.resourcepack.api.PackPath
import gg.grounds.resourcepack.api.VanillaPathClaim

/** Client-language branding for the vanilla pause screen, shared by every proxy. */
internal object PauseMenuPack {
    private const val LOGO = "\uE600"

    // Minecraft 26.3 asset index 34, pack.mcmeta SHA-1:
    // 6cb57c8bf7f43ecb75339be01e23b5be0c83f0c9. Keep the list pinned; builds are offline.
    private val languages =
        """
        af_za ar_sa ast_es az_az ba_ru bar be_by be_latn bg_bg br_fr brb bs_ba ca_es
        cs_cz cv_cu cy_gb da_dk de_at de_ch de_de el_gr en_au en_ca en_gb en_nz en_pt
        en_ud en_us enp enws eo_uy es_ar es_cl es_ec es_es es_mx es_uy es_ve esan
        et_ee eu_es fa_ir fi_fi fil_ph fo_fo fr_ca fr_ch fr_fr fra_de fur_it fy_nl
        ga_ie gd_gb gl_es go_fr got_de hal_ua haw_us he_il hi_in hn_no hr_hr hu_hu
        hy_am id_id ig_ng io_en is_is isv it_it ja_jp jbo_en ka_ge kk_kz kn_in ko_kr
        ksh kw_gb ky_kg la_la lb_lu li_li lmo lo_la lol_us lt_lt lv_lv lzh mk_mk
        mn_mn ms_my mt_mt nah nds_de nl_be nl_nl nn_no no_no oc_fr ovd pl_pl pls
        pt_br pt_pt qcb_es qid qya_aa ro_ro rpr ru_ru ry_ua sah_sah se_no sk_sk
        sl_si so_so sq_al sr_cs sr_sp sv_se sxu szl ta_in th_th tl_ph tlh_aa tok
        tr_tr tt_ru tzo_mx uk_ua uz_uz val_es vec_it vi_vn vp_vl vro yi_de yo_ng
        zh_cn zh_hk zh_tw zlm_arab
        """
            .trimIndent()
            .split(Regex("\\s+"))

    private val buttonLabels =
        mapOf(
            "en" to ("Back to Grounds" to "Leave Grounds"),
            "de" to ("Zurück zu Grounds" to "Grounds verlassen"),
            "fr" to ("Retour à Grounds" to "Quitter Grounds"),
            "es" to ("Volver a Grounds" to "Salir de Grounds"),
        )

    val vanillaClaims: Set<VanillaPathClaim> =
        (listOf("assets/minecraft/font/default.json") + languages.map(::languagePath))
            .map { VanillaPathClaim(PackPath.of(it)) }
            .toSet()

    fun entries(): List<PackEntry> =
        listOf(
            PackEntry.text(
                "assets/grounds/font/pause.json",
                """{"providers":[{"type":"bitmap","file":"grounds:font/tab_logo.png","height":26,"ascent":21,"chars":["$LOGO"]}]}""",
            ),
            // Font providers merge across packs. Add only our glyph, retaining vanilla's
            // normal/Unicode fallback providers and their filters from the lower pack.
            PackEntry.text(
                "assets/minecraft/font/default.json",
                """{"providers":[{"type":"reference","id":"grounds:pause"}]}""",
            ),
        ) +
            languages.map { language ->
                val labels = buttonLabels[language.substringBefore('_')]
                val json =
                    labels?.let { (back, disconnect) ->
                        """{"menu.game":"$LOGO","menu.returnToGame":"$back","menu.disconnect":"$disconnect"}"""
                    } ?: """{"menu.game":"$LOGO"}"""
                PackEntry.text(languagePath(language), json)
            }

    private fun languagePath(language: String): String = "assets/minecraft/lang/$language.json"
}
