package com.stellar.lang.badge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor

class TranslationBadgeHelperSpec : FunSpec({
    test("createBadge creates flag badge when not failed") {
        val badge = TranslationBadgeHelper.createBadge(failed = false, trailingSpace = true)
        badge.string shouldBe "${LanguageFlagHelper.FALLBACK_CHAR} "

        val defaultBadge = TranslationBadgeHelper.createBadge(failed = false)
        defaultBadge.string shouldBe "${LanguageFlagHelper.FALLBACK_CHAR} "

        val frBadge = TranslationBadgeHelper.createBadge(lang = "fr", failed = false, trailingSpace = true)
        frBadge.string shouldBe "${LanguageFlagHelper.getFlagChar("fr")} "

        val noSpaceBadge = TranslationBadgeHelper.createBadge(lang = "fr", failed = false, trailingSpace = false)
        noSpaceBadge.string shouldBe "${LanguageFlagHelper.getFlagChar("fr")}"
    }

    test("createBadge returns empty component when failed") {
        val badge = TranslationBadgeHelper.createBadge(failed = true, trailingSpace = false)
        badge.string shouldBe ""

        val defaultFailedBadge = TranslationBadgeHelper.createBadge(failed = true)
        defaultFailedBadge.string shouldBe ""

        val langFailedBadge = TranslationBadgeHelper.createBadge(lang = "fr", failed = true)
        langFailedBadge.string shouldBe ""
    }

    test("createTranslatingBadge creates gray badge") {
        val badge = TranslationBadgeHelper.createTranslatingBadge(trailingSpace = true)
        badge.string shouldBe "[...] "
        badge.style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.GRAY)

        val noSpace = TranslationBadgeHelper.createTranslatingBadge(trailingSpace = false)
        noSpace.string shouldBe "[...]"
        noSpace.style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.GRAY)
    }

    test("constant indicator colors match formatting colors") {
        TranslationBadgeHelper.INDICATOR_COLOR shouldBe 0x55FFFF
        TranslationBadgeHelper.INDICATOR_FAILED_COLOR shouldBe 0xFF5555
        TranslationBadgeHelper.INDICATOR_TRANSLATING_COLOR shouldBe 0xAAAAAA
    }

    test("createBadge and createTranslatingBadge return empty when hideIndicators is enabled") {
        val config = com.stellar.lang.service.TranslationService.getConfig()
        config.hideIndicators.setValue(true, false)
        try {
            TranslationBadgeHelper.isHidden() shouldBe true
            TranslationBadgeHelper.createBadge(failed = false).string shouldBe ""
            TranslationBadgeHelper.createBadge(lang = "fr", failed = false).string shouldBe ""
            TranslationBadgeHelper.createBadge(failed = true, trailingSpace = false).string shouldBe ""
            TranslationBadgeHelper.createTranslatingBadge(trailingSpace = true).string shouldBe ""
            TranslationBadgeHelper.createTranslatingBadge(trailingSpace = false).string shouldBe ""
        } finally {
            config.hideIndicators.setValue(false, false)
        }
    }

    test("default arguments and normal isHidden state") {
        TranslationBadgeHelper.isHidden() shouldBe false

        val badgeDefault = TranslationBadgeHelper.createBadge("fr")
        badgeDefault.string shouldBe "${LanguageFlagHelper.getFlagChar("fr")} "

        val badgeDefaultTrailing = TranslationBadgeHelper.createBadge("fr", failed = false)
        badgeDefaultTrailing.string shouldBe "${LanguageFlagHelper.getFlagChar("fr")} "

        val translatingDefault = TranslationBadgeHelper.createTranslatingBadge()
        translatingDefault.string shouldBe "[...] "
    }
})
