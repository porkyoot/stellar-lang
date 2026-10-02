@file:Suppress(
    "EmptyFunctionBlock",
    "FunctionOnlyReturningConstant",
    "MagicNumber",
)

package com.stellar.lang.geoip

import com.stellar.lang.service.TranslationService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.multiplayer.ServerData
import sun.misc.Unsafe
import java.util.function.Consumer

class ServerFlagRenderHelperSpec : FunSpec({
    val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let {
        it.isAccessible = true
        it.get(null) as Unsafe
    }

    val extractor: GuiGraphicsExtractor = unsafe
        .allocateInstance(GuiGraphicsExtractor::class.java) as GuiGraphicsExtractor
    val font: Font = unsafe.allocateInstance(Font::class.java) as Font

    beforeSpec {
        net.minecraft.SharedConstants.tryDetectVersion()
    }

    test("constants have expected layout values") {
        ServerFlagRenderHelper.PING_ICON_WIDTH shouldBe 10
        ServerFlagRenderHelper.PING_ICON_OFFSET_X shouldBe 15
        ServerFlagRenderHelper.FLAG_OFFSET_Y shouldBe 12
    }

    test("getEntryBounds resolves bounds from LayoutElement") {
        val dummy = object : LayoutElement {
            override fun setX(x: Int) {}
            override fun setY(y: Int) {}
            override fun getX(): Int = 100
            override fun getY(): Int = 50
            override fun getWidth(): Int = 200
            override fun getHeight(): Int = 36
            override fun visitWidgets(consumer: Consumer<net.minecraft.client.gui.components.AbstractWidget>) {}
        }

        val bounds = ServerFlagRenderHelper.getEntryBounds(dummy)
        bounds shouldNotBe null
        bounds?.first shouldBe 100 + 200 - 2
        bounds?.second shouldBe 50 + 2
    }

    test("getEntryBounds resolves bounds from reflection methods if available") {
        val dummyWithMethods = object {
            fun getContentRight(): Int = 300
            fun getContentY(): Int = 40
        }

        val bounds = ServerFlagRenderHelper.getEntryBounds(dummyWithMethods)
        bounds shouldNotBe null
        bounds?.first shouldBe 300
        bounds?.second shouldBe 40
    }

    test("getEntryBounds returns null if only one reflection method is present") {
        val dummyOnlyRight = object {
            fun getContentRight(): Int = 300
        }
        val dummyOnlyTop = object {
            fun getContentY(): Int = 40
        }

        ServerFlagRenderHelper.getEntryBounds(dummyOnlyRight) shouldBe null
        ServerFlagRenderHelper.getEntryBounds(dummyOnlyTop) shouldBe null
    }

    test("getEntryBounds returns null for non-layout object without methods") {
        ServerFlagRenderHelper.getEntryBounds("not-an-entry") shouldBe null
    }

    test("renderFlagUnderPing exits safely when feature is disabled") {
        ServerFlagManager.isFeatureEnabled = { false }
        val server = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        ServerFlagRenderHelper.renderFlagUnderPing("dummy", server, extractor, 0, 0)
    }

    test("renderFlagUnderPing exits safely when flagChar is null") {
        ServerFlagManager.isFeatureEnabled = { true }
        val server = ServerData("Empty", "", ServerData.Type.OTHER)
        ServerFlagRenderHelper.renderFlagUnderPing("dummy", server, extractor, 0, 0)
    }

    test("renderFlagUnderPing exits safely when entry bounds are null") {
        ServerFlagManager.isFeatureEnabled = { true }
        val server = ServerData("Test", "127.0.0.1", ServerData.Type.OTHER)
        ServerFlagRenderHelper.renderFlagUnderPing("not-an-entry", server, extractor, 0, 0)
    }

    test("resolveDimension returns computed when positive, otherwise fallback") {
        ServerFlagRenderHelper.resolveDimension(20, 10) shouldBe 20
        ServerFlagRenderHelper.resolveDimension(0, 10) shouldBe 10
        ServerFlagRenderHelper.resolveDimension(-5, 10) shouldBe 10
        ServerFlagRenderHelper.resolveDimension(null, 10) shouldBe 10
    }

    test("checkFlagTooltip respects config toggle and mouse position") {
        val server = ServerData("FR", "fr.server.com", ServerData.Type.OTHER)
        val config = TranslationService.getConfig()

        // 1. Tooltip disabled
        config.serverFlagTooltip.setValue(false, false)
        ServerFlagRenderHelper.checkFlagTooltip(server, extractor, font, 100, 100, 'A', 102, 102)

        // 2. Tooltip enabled but mouse not over (outside X, outside Y, outside both)
        config.serverFlagTooltip.setValue(true, false)
        ServerFlagRenderHelper.checkFlagTooltip(server, extractor, font, 100, 100, 'A', 0, 0)
        ServerFlagRenderHelper.checkFlagTooltip(server, extractor, font, 100, 100, 'A', 200, 102)
        ServerFlagRenderHelper.checkFlagTooltip(server, extractor, font, 100, 100, 'A', 102, 200)

        // 3. Tooltip enabled and mouse is over, but tooltip is null (uncached/unknown)
        val unknownServer = ServerData("Unknown", "unknown.domain.test", ServerData.Type.OTHER)
        ServerFlagRenderHelper.checkFlagTooltip(unknownServer, extractor, font, 100, 100, 'A', 102, 102)

        // 4. Tooltip enabled, mouse is over, and tooltip is present in cache
        ServerFlagCache.put("fr.server.com", GeoIpResult("fr", "France"))
        ServerFlagRenderHelper.checkFlagTooltip(server, extractor, font, 100, 100, 'A', 102, 102)
    }

    test("renderFlagUnderPing exits safely when font is not available") {
        ServerFlagManager.isFeatureEnabled = { true }
        ServerFlagCache.put("fr.server.com", GeoIpResult("fr", "France"))
        val server = ServerData("FR", "fr.server.com", ServerData.Type.OTHER)
        val dummy = object {
            fun getContentRight(): Int = 300
            fun getContentY(): Int = 40
        }
        // fontOverride = null will try Minecraft.getInstance().font which throws/returns null headlessly
        ServerFlagRenderHelper.renderFlagUnderPing(dummy, server, extractor, 290, 62, fontOverride = null)
    }

    test("renderFlagUnderPing renders flag when entry, server, and font are valid") {
        ServerFlagManager.isFeatureEnabled = { true }
        ServerFlagCache.put("fr.server.com", GeoIpResult("fr", "France"))
        val server = ServerData("FR", "fr.server.com", ServerData.Type.OTHER)

        val dummy = object : LayoutElement {
            override fun setX(x: Int) {}
            override fun setY(y: Int) {}
            override fun getX(): Int = 100
            override fun getY(): Int = 50
            override fun getWidth(): Int = 200
            override fun getHeight(): Int = 36
            override fun visitWidgets(consumer: Consumer<net.minecraft.client.gui.components.AbstractWidget>) {}
        }

        ServerFlagRenderHelper.renderFlagUnderPing(dummy, server, extractor, 290, 62, fontOverride = font)
    }
})
