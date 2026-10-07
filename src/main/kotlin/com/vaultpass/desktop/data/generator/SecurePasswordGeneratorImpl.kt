package com.vaultpass.desktop.data.generator

import com.vaultpass.desktop.domain.generator.EntropyRating
import com.vaultpass.desktop.domain.generator.GeneratedSecret
import com.vaultpass.desktop.domain.generator.PasswordConfig
import com.vaultpass.desktop.domain.generator.PasswordGenerator
import com.vaultpass.desktop.domain.generator.PassphraseConfig
import java.security.SecureRandom
import kotlin.math.ln

/**
 * Cryptographically secure generator for random passwords and Diceware passphrases.
 * Uses [SecureRandom] as CSPRNG source and estimates bit-entropy and crack-time ratings.
 */
class SecurePasswordGeneratorImpl(
    private val secureRandom: SecureRandom = SecureRandom()
) : PasswordGenerator {

    companion object {
        private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
        private const val NUMBERS = "0123456789"
        private const val SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?"

        // Ambiguous characters to exclude when avoidAmbiguous is true: 0, O, o, 1, l, I, |
        private const val UPPERCASE_UNAMBIGUOUS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val LOWERCASE_UNAMBIGUOUS = "abcdefghijkmnpqrstuvwxyz"
        private const val NUMBERS_UNAMBIGUOUS = "23456789"
        private const val SYMBOLS_UNAMBIGUOUS = "!@#$%^&*()_+-=[]{};:,.<>?"

        private val WORD_LIST = listOf(
            "acorn", "amber", "anchor", "anthem", "apex", "apple", "apron", "arch", "arena", "arrow",
            "atlas", "atom", "autumn", "badge", "baker", "bamboo", "banner", "basin", "beacon", "beam",
            "berry", "bison", "blade", "blanket", "bloom", "blossom", "bonfire", "boulder", "breeze", "bridge",
            "brook", "cabin", "cactus", "canyon", "castle", "cedar", "cider", "cliff", "cloak", "clover",
            "coast", "comet", "compass", "copper", "coral", "crater", "creek", "crest", "crystal", "current",
            "delta", "desert", "diamond", "dolphin", "dragon", "drift", "dune", "eagle", "echo", "eclipse",
            "ember", "emerald", "falcon", "fawn", "feather", "fern", "finch", "fjord", "flame", "flint",
            "forest", "fossil", "fountain", "fox", "frost", "galaxy", "garden", "garnet", "gateway", "glacier",
            "glade", "glen", "glow", "granite", "grove", "harbor", "harvest", "haven", "hawk", "hazel",
            "heather", "helix", "heron", "horizon", "humming", "hunter", "icicle", "igloo", "island", "ivy",
            "jade", "jasper", "jester", "journey", "jungle", "jupiter", "kelp", "kestrel", "kingdom", "lagoon",
            "lantern", "lark", "laurel", "legend", "lemon", "leopard", "lighthouse", "lily", "lion", "lizard",
            "lotus", "lumber", "lunar", "lynx", "magnet", "magnolia", "mango", "maple", "marble", "marsh",
            "meadow", "melody", "meteor", "midnight", "mineral", "mist", "monarch", "moon", "moss", "mountain",
            "nebula", "nectar", "nest", "nickel", "night", "nova", "oasis", "ocean", "olive", "onyx",
            "opal", "orbit", "orchid", "otter", "owl", "palace", "panther", "parrot", "passage", "pathway",
            "peak", "pearl", "pebble", "pelican", "penguin", "pepper", "petal", "phoenix", "pigeon", "pilot",
            "pine", "pioneer", "planet", "plasma", "plateau", "plover", "polar", "ponder", "poppy", "prairie",
            "prism", "pulse", "python", "quartz", "quiver", "radar", "radiant", "rafter", "rainbow", "raven",
            "reef", "rhyme", "ridge", "river", "robin", "rocket", "ruby", "saddle", "safari", "sailor",
            "salmon", "sand", "sapphire", "saturn", "savanna", "scarlet", "scenic", "scholar", "scout", "sea",
            "season", "sequoia", "serenity", "shadow", "shimmer", "shore", "silver", "skylark", "solace", "solar",
            "sparrow", "sphere", "sphinx", "spider", "spire", "spring", "spruce", "star", "station", "stream",
            "summit", "sunflower", "sunrise", "sunset", "surf", "swift", "sylvan", "talon", "tapestry", "tarn",
            "teal", "temple", "thistle", "thunder", "tide", "tiger", "timber", "titan", "topaz", "torrent",
            "tower", "trail", "transit", "tribute", "tulip", "tundra", "twilight", "valley", "vapor", "velvet",
            "venture", "verdant", "vessel", "vibrant", "village", "vine", "violet", "viper", "vista", "volcano",
            "voyage", "walnut", "wander", "warden", "wave", "weaver", "willow", "wind", "winter", "wizard",
            "wolf", "wren", "yarrow", "zenith", "zephyr", "zodiac"
        )
    }

    override fun generatePassword(config: PasswordConfig): GeneratedSecret {
        val upper = if (config.avoidAmbiguous) UPPERCASE_UNAMBIGUOUS else UPPERCASE
        val lower = if (config.avoidAmbiguous) LOWERCASE_UNAMBIGUOUS else LOWERCASE
        val digits = if (config.avoidAmbiguous) NUMBERS_UNAMBIGUOUS else NUMBERS
        val symbols = if (config.avoidAmbiguous) SYMBOLS_UNAMBIGUOUS else SYMBOLS

        val enabledSets = mutableListOf<String>()
        if (config.includeUppercase) enabledSets.add(upper)
        if (config.includeLowercase) enabledSets.add(lower)
        if (config.includeNumbers) enabledSets.add(digits)
        if (config.includeSymbols) enabledSets.add(symbols)

        // Fallback to lowercase if no character set is selected
        if (enabledSets.isEmpty()) {
            enabledSets.add(lower)
        }

        val pool = enabledSets.joinToString("")
        val length = config.length.coerceAtLeast(1)

        val resultChars = CharArray(length)
        var index = 0

        // Guarantee at least 1 character from each enabled set (up to length)
        for (charSet in enabledSets) {
            if (index < length) {
                resultChars[index++] = charSet[secureRandom.nextInt(charSet.length)]
            }
        }

        // Fill remainder from the full pool
        while (index < length) {
            resultChars[index++] = pool[secureRandom.nextInt(pool.length)]
        }

        // Fisher-Yates shuffle with SecureRandom to avoid positional bias
        for (i in resultChars.indices.reversed()) {
            val j = secureRandom.nextInt(i + 1)
            val temp = resultChars[i]
            resultChars[i] = resultChars[j]
            resultChars[j] = temp
        }

        val passwordString = String(resultChars)
        val entropy = calculatePasswordEntropy(length, pool.length)
        val rating = calculateRating(entropy)
        val crackTime = estimateCrackTime(entropy)

        return GeneratedSecret(
            value = passwordString,
            entropyBits = entropy,
            rating = rating,
            crackTimeEstimate = crackTime
        )
    }

    override fun generatePassphrase(config: PassphraseConfig): GeneratedSecret {
        val wordCount = config.wordCount.coerceAtLeast(1)
        val chosenWords = ArrayList<String>(wordCount)

        for (i in 0 until wordCount) {
            val word = WORD_LIST[secureRandom.nextInt(WORD_LIST.size)]
            val formatted = if (config.capitalize) {
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            } else {
                word
            }
            chosenWords.add(formatted)
        }

        if (config.includeNumber) {
            val randomDigit = secureRandom.nextInt(10)
            val lastIndex = chosenWords.size - 1
            chosenWords[lastIndex] = chosenWords[lastIndex] + randomDigit
        }

        val passphraseString = chosenWords.joinToString(config.separator)
        val entropy = calculatePassphraseEntropy(config, WORD_LIST.size)
        val rating = calculateRating(entropy)
        val crackTime = estimateCrackTime(entropy)

        return GeneratedSecret(
            value = passphraseString,
            entropyBits = entropy,
            rating = rating,
            crackTimeEstimate = crackTime
        )
    }

    override fun calculatePasswordEntropy(length: Int, poolSize: Int): Float {
        if (length <= 0 || poolSize <= 1) return 0.0f
        return (length * (ln(poolSize.toDouble()) / ln(2.0))).toFloat()
    }

    override fun calculatePassphraseEntropy(config: PassphraseConfig, wordlistSize: Int): Float {
        if (config.wordCount <= 0 || wordlistSize <= 1) return 0.0f
        val wordBits = config.wordCount * (ln(wordlistSize.toDouble()) / ln(2.0))
        val numberBits = if (config.includeNumber) (ln(10.0) / ln(2.0)) else 0.0
        return (wordBits + numberBits).toFloat()
    }

    private fun calculateRating(entropy: Float): EntropyRating = when {
        entropy < 40f -> EntropyRating.VERY_WEAK
        entropy < 65f -> EntropyRating.WEAK
        entropy < 80f -> EntropyRating.MEDIUM
        entropy < 100f -> EntropyRating.STRONG
        else -> EntropyRating.VERY_STRONG
    }

    private fun estimateCrackTime(entropy: Float): String = when {
        entropy < 40f -> "Instantly"
        entropy < 65f -> "Few hours to days"
        entropy < 80f -> "Several years"
        entropy < 100f -> "Centuries"
        else -> "Millions of years"
    }
}
