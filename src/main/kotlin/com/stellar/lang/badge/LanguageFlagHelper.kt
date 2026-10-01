@file:Suppress(
    "TooManyFunctions",
    "CognitiveComplexMethod",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LabeledExpression",
    "MagicNumber",
)

package com.stellar.lang.badge

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages pixel-art and emoji flag resolution for ISO languages and countries.
 */
object LanguageFlagHelper {
    const val FALLBACK_CHAR: Char = '\uE100' // Globe flag
    private const val GLOBE_KEY = "globe"
    private const val UNKNOWN_KEY = "unknown"
    private const val GLOBE_EMOJI = "\uD83C\uDF10" // 🌐
    private const val REGIONAL_INDICATOR_BASE = 0x1F1E6

    private const val KEYS_STR =
        "globe,eu,un,eo,ad,ae,af,ag,al,am,ao,ar,as,at,au,aw,az,ba,bb,bd,be,bf,bg,bh,bi,bj,bm,bn,bo,br,bs,bt,bw," +
            "by,bz,ca,cd,cf,cg,ch,ci,ck,cl,cm,cn,co,cr,cu,cv,cy,cz,de,dj,dk,dm,do,dz,ec,ee,eg,er,es,et,fi,fj,fm," +
            "fr,ga,gb,gd,ge,gh,gm,gn,gq,gr,gt,gu,gw,gy,hk,hn,hr,ht,hu,id,ie,il,in,iq,ir,is,it,jm,jo,jp,ke,kg,kh," +
            "ki,km,kn,kp,kr,kw,ky,kz,la,lb,lc,li,lk,lr,ls,lt,lu,lv,ly,ma,mc,md,me,mg,mh,mk,ml,mm,mn,mr,ms,mt,mu," +
            "mv,mw,mx,my,mz,na,ne,ng,ni,nl,no,np,nr,nz,om,pa,pe,pg,ph,pk,pl,pr,ps,pt,pw,py,qa,ro,rs,ru,rw,sa,sb," +
            "sc,sd,se,sg,si,sk,sl,sm,sn,so,sr,st,sv,sy,sz,td,tg,th,tj,tl,tm,tn,to,tr,tt,tv,tw,tz,ua,ug,us,uy,uz," +
            "vc,ve,vi,vn,vu,ws,ye,za,zm,zw"

    private const val ALIASES_STR =
        "en:us ja:jp ko:kr zh:cn el:gr da:dk sv:se cs:cz uk:ua he:il ar:sa hi:in bn:bd ur:pk fa:ir vi:vn ca:es " +
            "eu:es gl:es ga:ie cy:gb gd:gb af:za sw:ke tl:ph fil:ph sl:si et:ee sq:al hy:am ka:ge ta:in te:in " +
            "mr:in gu:in kn:in ml:in pa:in ne:np si:lk km:kh lo:la my:mm kk:kz uz:uz be:by bs:ba sr:rs mk:mk " +
            "eo:eo la:it fo:dk gl:dk im:gb pf:fr"

    private val KEY_TO_CHAR: Map<String, Char> = buildMap {
        KEYS_STR.split(',').forEachIndexed { idx, key ->
            put(key, ('\uE100'.code + idx).toChar())
        }
    }

    private val ISO639_TO_COUNTRY: Map<String, String> = buildMap {
        ALIASES_STR.split(' ').forEach { pair ->
            val parts = pair.split(':')
            if (parts.size == 2) {
                put(parts[0], parts[1])
            }
        }
    }

    private val flagCharCache = ConcurrentHashMap<String, Char>()
    private val flagEmojiCache = ConcurrentHashMap<String, String>()
    private val languageNameCache = ConcurrentHashMap<String, String>()

    fun resolveCountryCode(langOrCountry: String?): String {
        if (langOrCountry.isNullOrBlank()) return GLOBE_KEY
        val trimmed = langOrCountry.trim().lowercase(Locale.ROOT)

        // 1. Direct match with country code or special key
        if (KEY_TO_CHAR.containsKey(trimmed)) return trimmed

        // 2. Handle composite locales e.g. "en_US", "en-GB", "pt_BR", "zh_CN"
        val sepIdx = trimmed.indexOfFirst { it == '_' || it == '-' }
        if (sepIdx != -1) {
            val langPart = trimmed.substring(0, sepIdx)
            val regionPart = trimmed.substring(sepIdx + 1)
            if (KEY_TO_CHAR.containsKey(regionPart)) return regionPart
            val mappedLang = ISO639_TO_COUNTRY[langPart]
            if (mappedLang != null && KEY_TO_CHAR.containsKey(mappedLang)) return mappedLang
            if (KEY_TO_CHAR.containsKey(langPart)) return langPart
        }

        // 3. Match through ISO 639-1 alias table
        val mapped = ISO639_TO_COUNTRY[trimmed]
        if (mapped != null && KEY_TO_CHAR.containsKey(mapped)) return mapped

        return GLOBE_KEY
    }

    fun getFlagChar(langOrCountry: String?): Char {
        if (langOrCountry.isNullOrBlank()) return FALLBACK_CHAR
        return flagCharCache.computeIfAbsent(langOrCountry) {
            val code = resolveCountryCode(it)
            KEY_TO_CHAR[code] ?: FALLBACK_CHAR
        }
    }

    fun getFlagEmoji(langOrCountry: String?): String {
        if (langOrCountry.isNullOrBlank()) return GLOBE_EMOJI
        return flagEmojiCache.computeIfAbsent(langOrCountry) {
            val countryCode = resolveCountryCode(it)
            if (countryCode == GLOBE_KEY || countryCode == UNKNOWN_KEY) return@computeIfAbsent GLOBE_EMOJI
            val emojiOpt = net.fellbaum.jemoji.EmojiManager.getByDiscordAlias("flag_$countryCode")
            if (emojiOpt.isPresent) {
                return@computeIfAbsent emojiOpt.get().emoji
            }
            if (countryCode.length == 2 && countryCode.all { c -> c in 'a'..'z' }) {
                val first = Character.toChars(REGIONAL_INDICATOR_BASE + (countryCode[0].code - 'a'.code))
                val second = Character.toChars(REGIONAL_INDICATOR_BASE + (countryCode[1].code - 'a'.code))
                return@computeIfAbsent String(first) + String(second)
            }
            GLOBE_EMOJI
        }
    }

    fun getLanguageName(langOrCountry: String?): String {
        if (langOrCountry.isNullOrBlank()) return "Unknown"
        return languageNameCache.computeIfAbsent(langOrCountry) { key ->
            val tag = key.replace('_', '-')
            val locale = Locale.forLanguageTag(tag)
            val display = locale.getDisplayName(Locale.ENGLISH)
            if (display.isNotBlank() && !display.equals(tag, ignoreCase = true)) {
                display
            } else {
                key.uppercase(Locale.ENGLISH)
            }
        }
    }

    fun isFlagChar(c: Char): Boolean = c in '\uE100'..'\uE1CF'

    fun isFlagPrefix(text: String): Boolean {
        if (text.isEmpty()) return false
        val first = text[0]
        return isFlagChar(first)
    }

    fun stripFlagPrefix(text: String): String {
        if (!isFlagPrefix(text)) return text
        var idx = 1
        while (idx < text.length && text[idx] == ' ') {
            idx++
        }
        return text.substring(idx)
    }

    fun createFlagBadge(langOrCountry: String?, trailingSpace: Boolean = true): MutableComponent {
        val ch = getFlagChar(langOrCountry)
        val text = if (trailingSpace) "$ch " else "$ch"
        return Component.literal(text).withStyle(ChatFormatting.WHITE)
    }

    fun clearCache() {
        flagCharCache.clear()
        flagEmojiCache.clear()
        languageNameCache.clear()
    }
}
