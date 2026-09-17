package com.stellar.lang.config

import com.stellar.core.config.ConfigManager
import com.stellar.core.input.Key
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class StellarLangConfigSpec : FunSpec({
    test("StellarLangConfig registers with expected defaults") {
        val testId = "test_${System.nanoTime()}"
        val config = ConfigManager.register("stellar_lang", testId, StellarLangConfig::class.java)
        config shouldNotBe null
        config.enabled.value() shouldBe true
        config.apiHost.value() shouldBe "https://libretranslate.com"
        config.apiKey.value() shouldBe ""
        config.targetLanguage.value() shouldBe "en"
        config.translateChat.value() shouldBe true
        config.translateSigns.value() shouldBe true
        config.translateBooks.value() shouldBe true
        config.translateEntities.value() shouldBe true
        config.translateItems.value() shouldBe true
        config.showOriginalKey.value() shouldBe Key.KEY_COMMA

        config.enabled.setValue(false, true)
        config.enabled.value() shouldBe false

        config.targetLanguage.setValue("es", true)
        config.targetLanguage.value() shouldBe "es"

        config.showOriginalKey.setValue(Key.KEY_PERIOD, true)
        config.showOriginalKey.value() shouldBe Key.KEY_PERIOD
    }
})
