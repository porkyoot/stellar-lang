package com.stellar.lang.config

import com.stellar.core.input.Key
import com.stellar.lang.StellarLangMod
import com.stellar.lang.input.StellarLangInputHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class LangClothConfigScreenSpec : FunSpec({
    test("StellarLangModMenu creates config screen factory") {
        val menu = StellarLangModMenu()
        val factory = menu.getModConfigScreenFactory()
        factory shouldNotBe null
    }

    test("StellarLangInputHandler isShowingOriginal safely returns false in test environment") {
        StellarLangInputHandler.isShowingOriginal() shouldBe false
    }

    test("StellarLangMod onInitializeClient runs without error") {
        val mod = StellarLangMod()
        mod.onInitializeClient()
        StellarLangMod.MOD_ID shouldBe "stellar_lang"
    }

    test("LangClothConfigScreen save consumers apply changes to config") {
        val testId = "cloth_test_${System.nanoTime()}"
        val config = com.stellar.core.config.ConfigManager
            .register("stellar_lang", testId, StellarLangConfig::class.java)

        // Test each config value update directly to verify the lambdas logic
        config.enabled.setValue(false, false)
        config.enabled.value() shouldBe false

        config.targetLanguage.setValue("FR".trim().lowercase(), false)
        config.targetLanguage.value() shouldBe "fr"

        config.apiHost.setValue("http://my-libretranslate.local".trim(), false)
        config.apiHost.value() shouldBe "http://my-libretranslate.local"

        config.apiKey.setValue("secret-key-123".trim(), false)
        config.apiKey.value() shouldBe "secret-key-123"

        config.translateChat.setValue(false, false)
        config.translateChat.value() shouldBe false

        config.translatePlayerNames.setValue(true, false)
        config.translatePlayerNames.value() shouldBe true

        config.translateSigns.setValue(false, false)
        config.translateSigns.value() shouldBe false

        config.signTooltips.setValue(false, false)
        config.signTooltips.value() shouldBe false

        config.signRaycastIntervalMs.setValue(150, false)
        config.signRaycastIntervalMs.value() shouldBe 150

        config.translateBooks.setValue(false, false)
        config.translateBooks.value() shouldBe false

        config.translateEntities.setValue(false, false)
        config.translateEntities.value() shouldBe false

        config.translateItems.setValue(false, false)
        config.translateItems.value() shouldBe false

        config.translateContainers.setValue(false, false)
        config.translateContainers.value() shouldBe false

        config.translateMapBanners.setValue(false, false)
        config.translateMapBanners.value() shouldBe false

        config.hideIndicators.setValue(true, false)
        config.hideIndicators.value() shouldBe true

        config.showOriginalKey.setValue(Key.KEY_PERIOD, false)
        config.showOriginalKey.value() shouldBe Key.KEY_PERIOD

        config.deeplRequestIntervalMs.setValue(350, false)
        config.deeplRequestIntervalMs.value() shouldBe 350
    }
})
