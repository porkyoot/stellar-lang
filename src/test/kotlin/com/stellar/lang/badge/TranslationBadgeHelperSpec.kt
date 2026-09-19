package com.stellar.lang.badge

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor

class TranslationBadgeHelperSpec : FunSpec({
    test("createBadge creates cyan bold badge when not failed") {
        val badge = TranslationBadgeHelper.createBadge(failed = false, trailingSpace = true)
        badge.string shouldBe "[T] "
        badge.style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.AQUA)
        badge.style.isBold shouldBe true
        badge.style.isStrikethrough shouldBe false

        val defaultBadge = TranslationBadgeHelper.createBadge(failed = false)
        defaultBadge.string shouldBe "[T] "
    }

    test("createBadge creates red bold strikethrough badge when failed") {
        val badge = TranslationBadgeHelper.createBadge(failed = true, trailingSpace = false)
        badge.string shouldBe "[T]"
        badge.style.color shouldBe TextColor.fromLegacyFormat(ChatFormatting.RED)
        badge.style.isBold shouldBe true
        badge.style.isStrikethrough shouldBe true

        val defaultFailedBadge = TranslationBadgeHelper.createBadge(failed = true)
        defaultFailedBadge.string shouldBe "[T] "
    }

    test("constant indicator colors match formatting colors") {
        TranslationBadgeHelper.INDICATOR_COLOR shouldBe 0x55FFFF
        TranslationBadgeHelper.INDICATOR_FAILED_COLOR shouldBe 0xFF5555
    }
})
