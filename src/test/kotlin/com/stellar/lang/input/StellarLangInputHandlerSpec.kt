package com.stellar.lang.input

import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StellarLangInputHandlerSpec : FunSpec({
    beforeEach {
        StellarLangInputHandler.keyStateProvider = null
        StellarLangInputHandler.windowProvider = null
    }

    afterEach {
        StellarLangInputHandler.keyStateProvider = null
        StellarLangInputHandler.windowProvider = null
    }

    test("isShowingOriginal uses keyStateProvider when provided") {
        StellarLangInputHandler.keyStateProvider = { key -> key == 44 }
        val config = TranslationService.getConfig()
        config.showOriginalKey.setValue(44, false)
        StellarLangInputHandler.isShowingOriginal() shouldBe true

        config.showOriginalKey.setValue(45, false)
        StellarLangInputHandler.isShowingOriginal() shouldBe false
    }

    test("isShowingOriginal falls back to window provider and checkKeyDown") {
        StellarLangInputHandler.keyStateProvider = null
        StellarLangInputHandler.windowProvider = { null }
        StellarLangInputHandler.isShowingOriginal() shouldBe false

        StellarLangInputHandler.checkKeyDown(null, 44) shouldBe false
    }

    test("isShowingOriginal falls back safely when windowProvider is null") {
        StellarLangInputHandler.keyStateProvider = null
        StellarLangInputHandler.windowProvider = null
        StellarLangInputHandler.isShowingOriginal() shouldBe false
    }
})
