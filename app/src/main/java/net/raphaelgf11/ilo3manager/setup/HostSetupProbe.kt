package net.raphaelgf11.ilo3manager.setup

import java.security.SecureRandom

/**
 * What the setup assistant reads out of the iLO, and what it sends it.
 *
 * Text in, text out, so the parts that decide what gets written to a user's BMC — above all the
 * account-creation command — are settled without a device. The samples the tests use come from a
 * real iLO 3 at firmware 1.94.
 */

/**
 * The iLO firmware this app has actually been exercised against.
 *
 * Everything the assistant relies on — the CLP node names, the account verbs, the ping output, the
 * limits IPMI imposes — was measured on this version. An older one may differ, and is in any case
 * missing whatever HP has fixed since.
 */
const val TESTED_ILO_VERSION = "1.94"

/** The firmware an iLO reports for itself, from `show /map1/firmware1`. */
data class IloFirmware(val version: String, val date: String)

fun parseIloFirmware(output: String): IloFirmware {
    fun property(key: String) = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.removePrefix("$key=")
        ?.trim()
        .orEmpty()

    return IloFirmware(property("version"), property("date"))
}

/**
 * Compares two dotted versions numerically, part by part.
 *
 * Textual comparison would put 1.9 after 1.88, which is how firmware versions are routinely
 * misread: the parts are numbers, not decimals.
 */
fun compareIloVersions(left: String, right: String): Int {
    val a = left.split('.').map { it.trim().toIntOrNull() ?: 0 }
    val b = right.split('.').map { it.trim().toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val diff = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/**
 * Whether this iLO runs something older than what the app was tested on.
 *
 * An unreadable version is not treated as old: saying "your firmware is out of date" on the
 * strength of a string nobody could parse would be a guess dressed as a finding.
 */
fun isOlderThanTested(version: String): Boolean =
    version.isNotBlank() &&
        version.split('.').any { it.trim().toIntOrNull() != null } &&
        compareIloVersions(version, TESTED_ILO_VERSION) < 0

/**
 * Whether a reply came from an iLO's command line rather than from an ordinary shell.
 *
 * Pointing the assistant at any machine running sshd otherwise succeeds quietly: `show
 * /map1/config1` is not a command a shell knows, but the property parsers simply find nothing and
 * hand back blanks, which read as "this iLO declares no ports" rather than "this is not an iLO".
 *
 * Judged on the answer rather than on the SSH banner, so it holds whatever HP ships as its SSH
 * implementation: the CLP stamps every reply with a status tag, and prefixes its own extensions
 * with `oemhp_`. A shell produces neither — the test machine answered
 * `bash: show : commande introuvable`.
 */
fun looksLikeIloCli(output: String): Boolean =
    output.contains("status_tag=", ignoreCase = true) && output.contains("oemhp_", ignoreCase = true)

/** The access settings the assistant needs, read from `show /map1/config1`. */
data class IloAccessConfig(
    val sshPort: Int?,
    val httpsPort: Int?,
    /** The iLO refuses shorter passwords, so a generated one has to clear it. */
    val minPasswordLength: Int?,
)

fun parseIloAccessConfig(output: String): IloAccessConfig {
    fun intProperty(name: String): Int? = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=") }
        ?.removePrefix("$name=")
        ?.trim()
        ?.toIntOrNull()

    return IloAccessConfig(
        sshPort = intProperty("oemhp_sshport"),
        httpsPort = intProperty("oemhp_sslport"),
        minPasswordLength = intProperty("oemhp_minpwdlen"),
    )
}

/**
 * Characters a generated password may use.
 *
 * Deliberately narrow. The password travels inside `create ... password=<value> ...`, which the
 * iLO's own command parser splits on spaces, commas and equals signs — a password containing one
 * would be silently truncated, leaving an account whose password is not the one the app stored.
 * Quotes and backslashes are out for the same reason.
 */
private const val PASSWORD_ALPHABET =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#%*+-./:?@^_~"

/** iLO 3 rejects anything longer; asking for more produces an account that cannot be logged into. */
const val ILO_MAX_PASSWORD_LENGTH = 39

/**
 * The longest password IPMI can authenticate with.
 *
 * RAKP carries the password in a fixed 20-byte field, so a longer one cannot be presented at all —
 * clients refuse outright rather than truncate. The iLO itself happily accepts longer passwords for
 * SSH and the web interface, which is what makes this trap quiet: the account works everywhere
 * except IPMI, and the failure reads as "this server does not do IPMI".
 *
 * Measured, not assumed: an account created with a 24-character password authenticated over SSH and
 * was refused by IPMI on the test machine, with `ipmitool` reporting the limit verbatim.
 */
const val IPMI_MAX_PASSWORD_LENGTH = 20

/**
 * A password for the dedicated account.
 *
 * Exactly [IPMI_MAX_PASSWORD_LENGTH] by default rather than as long as the iLO would allow: the
 * account this generates is the one the app will use for everything, IPMI included, and a longer
 * password would silently cost that. Twenty characters out of this alphabet is far beyond guessing
 * anyway, so the longer password buys nothing against what it breaks.
 *
 * An iLO whose own minimum exceeds that limit is honoured instead — its accounts simply cannot use
 * IPMI, whatever this generates; see [dedicatedAccountLosesIpmi].
 */
fun generateStrongPassword(
    minLength: Int?,
    random: java.util.Random = SecureRandom(),
): String {
    val floor = minLength ?: 0
    val length = maxOf(floor, IPMI_MAX_PASSWORD_LENGTH).coerceAtMost(ILO_MAX_PASSWORD_LENGTH)
    return (1..length)
        .map { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }
        .joinToString("")
}

/**
 * Whether this iLO's own password policy forces generated accounts out of IPMI.
 *
 * Worth saying out loud: without it the assistant would report IPMI as unavailable and the user
 * would look for the cause on the server rather than in its password policy.
 */
fun dedicatedAccountLosesIpmi(minPasswordLength: Int?): Boolean =
    (minPasswordLength ?: 0) > IPMI_MAX_PASSWORD_LENGTH

/**
 * Longest account name this firmware accepts.
 *
 * Measured: 39 characters is created successfully, 40 is refused with `COMMAND SYNTAX ERROR`.
 */
const val ILO_MAX_USERNAME_LENGTH = 39

/** Marks an account as belonging to this app, in the middle of the composed name. */
private const val DEDICATED_MARKER = "-I3M"

/** The suffix that distinguishes several installations sharing one iLO account name. */
private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * Keeps only what the iLO's command parser can carry unaltered.
 *
 * Device model names are the reason: they arrive from `Build.MODEL` and routinely hold spaces, and
 * on emulators things like `Standard_PC__i440FX___PIIX__1996_`.
 */
private fun sanitizeNamePart(value: String): String =
    value.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-{2,}"), "-")

/**
 * Longest account name IPMI can authenticate with.
 *
 * Measured on the test machine, the same way the password limit was: a 16-character account logs
 * in over IPMI, 17 and 20 are refused with `Username is too long (> 16 bytes)`. The iLO itself
 * accepts up to [ILO_MAX_USERNAME_LENGTH], which is what makes this quiet — the account works over
 * SSH and the web and fails only on IPMI, reading as "this server does not do IPMI".
 */
const val IPMI_MAX_USERNAME_LENGTH = 16

/** Below this the account name stops identifying anyone, so the model is cut instead. */
private const val MIN_ACCOUNT_PART = 3

/**
 * The login name proposed for this phone, such as `rga-RKY-LX1-I3M0`.
 *
 * It carries who authorised it, which device holds it, and that this app created it, so the account
 * list on the iLO stays readable and revoking one phone means deleting the line that names it.
 *
 * Fitted to [IPMI_MAX_USERNAME_LENGTH] and made as long as that allows, because a login name over
 * that limit silently costs IPMI — and IPMI is what makes the power state, the front-panel widget
 * and the background checks quick. What gets cut when it does not fit: the account name first, down
 * to [MIN_ACCOUNT_PART] characters, then the model. The full, untruncated description still reaches
 * the iLO through the display name; see [dedicatedAccountDisplayName].
 */
fun defaultDedicatedUsername(iloUsername: String, deviceModel: String): String {
    val user = sanitizeNamePart(iloUsername)
    val model = sanitizeNamePart(deviceModel)
    val suffix = DEDICATED_MARKER + HEX_DIGITS[0]

    if (user.isEmpty() || model.isEmpty()) {
        val single = user.ifEmpty { model }
        return single.take(IPMI_MAX_USERNAME_LENGTH - suffix.length).trim('-') + suffix
    }

    // What the two halves share, once the separator and the marker are paid for.
    val budget = IPMI_MAX_USERNAME_LENGTH - suffix.length - 1
    var keptUser = user
    var keptModel = model
    if (keptUser.length + keptModel.length > budget) {
        keptUser = user.take((budget - keptModel.length).coerceAtLeast(MIN_ACCOUNT_PART))
        if (keptUser.length + keptModel.length > budget) {
            keptModel = model.take((budget - keptUser.length).coerceAtLeast(1))
        }
    }
    // Trimming a truncation that landed on a separator costs one character and saves a name
    // ending in a dangling dash.
    return "${keptUser.trim('-')}-${keptModel.trim('-')}$suffix"
}

/**
 * The untruncated description, for the iLO's display-name field.
 *
 * The login name has to fit sixteen characters; this one may run to [ILO_MAX_USERNAME_LENGTH], and
 * the iLO shows it beside the login name in its own account list. It is where the part cut out of
 * the login name survives — the phone model above all, which is what tells two devices apart.
 *
 * [username] supplies the hex suffix, so both names carry the same one.
 */
fun dedicatedAccountDisplayName(iloUsername: String, deviceModel: String, username: String): String {
    val user = sanitizeNamePart(iloUsername)
    val model = sanitizeNamePart(deviceModel)
    val suffix = DEDICATED_MARKER + (username.lastOrNull()?.uppercaseChar() ?: HEX_DIGITS[0])

    val full = listOf(user, model).filter { it.isNotEmpty() }.joinToString("-") + suffix
    if (full.length <= ILO_MAX_USERNAME_LENGTH) return full

    // Same order of sacrifice as the login name: the account first, then the model.
    val budget = ILO_MAX_USERNAME_LENGTH - suffix.length - 1
    val keptUser = user.take((budget - model.length).coerceAtLeast(MIN_ACCOUNT_PART))
    val keptModel = model.take((budget - keptUser.length).coerceAtLeast(1))
    return "${keptUser.trim('-')}-${keptModel.trim('-')}$suffix"
}

/** Whether a name carries this app's marker and hex suffix, and so has sixteen variants. */
fun isComposedDedicatedName(name: String): Boolean =
    Regex("^.*${Regex.escape(DEDICATED_MARKER)}[0-9A-Fa-f]$").matches(name)

/**
 * Resolves [desired] against the accounts already on the iLO, returning null when nothing is free.
 *
 * A name this app composed ends in a hex digit, and a taken one simply moves to the next: two
 * phones, or a reinstall after the old account was left behind, then coexist instead of colliding.
 * A name the user typed themselves is taken literally — renaming it under them would be worse than
 * saying it is taken.
 */
fun nextFreeDedicatedUsername(desired: String, existing: List<String>): String? {
    fun taken(name: String) = existing.any { it.equals(name, ignoreCase = true) }

    if (!isComposedDedicatedName(desired)) return desired.takeUnless { taken(it) }

    val base = desired.dropLast(1)
    return HEX_DIGITS.map { base + it }.firstOrNull { !taken(it) }
}

/** Every privilege this iLO firmware knows, as `create`/`set` spell them. */
const val ILO_ALL_PRIVILEGES = "admin,config,oemhp_rc,oemhp_power,oemhp_vm"

/**
 * Whether a value can be sent through the CLP without being mangled.
 *
 * The parser splits on whitespace, commas and equals signs, and has no quoting the app can rely on,
 * so a name carrying one of those would produce something other than what was asked for.
 */
fun isCplSafe(value: String): Boolean =
    value.isNotBlank() && value.none { it.isWhitespace() || it == ',' || it == '=' || it == '"' || it == '\'' }

/**
 * The command that creates the dedicated account, with every privilege.
 *
 * Full privileges because the app's own features span all of them — power, virtual media, the
 * remote console — and because an account short of one fails at the moment that feature is used
 * rather than at login, which is far harder to attribute.
 */
fun createAccountCommand(username: String, password: String, displayName: String): String {
    require(isCplSafe(username)) { "Le nom d'utilisateur ne doit contenir ni espace, ni virgule, ni signe égal." }
    require(isCplSafe(password)) { "Le mot de passe généré contient un caractère que l'iLO ne saurait pas lire." }
    require(isCplSafe(displayName)) { "Le nom affiché ne doit contenir ni espace, ni virgule, ni signe égal." }
    return "create /map1/accounts1 username=$username password=$password " +
        "name=$displayName group=$ILO_ALL_PRIVILEGES"
}

/**
 * The server's own name, read from `show /system1`.
 *
 * `oemhp_server_name` is what the administrator set; `name` is the model the iLO reports for
 * itself. Either beats an IP address in a list of servers, which is what the user would otherwise
 * be left reading.
 */
fun parseServerName(output: String): String {
    fun property(key: String) = output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.removePrefix("$key=")
        ?.trim()
        .orEmpty()

    return property("oemhp_server_name").ifBlank { property("name") }
}

/** Whether the account list already holds [username], read from `show /map1/accounts1`. */
fun accountExists(output: String, username: String): Boolean =
    parseAccountNames(output).any { it.equals(username, ignoreCase = true) }

/**
 * The account names in `show /map1/accounts1`.
 *
 * They are listed as targets, between the `Targets` and `Properties` headings — everything after
 * `Properties` belongs to the node itself and is not an account.
 */
fun parseAccountNames(output: String): List<String> {
    val lines = output.lines().map { it.trim() }
    val start = lines.indexOf("Targets")
    if (start < 0) return emptyList()
    return lines.drop(start + 1)
        .takeWhile { it != "Properties" && it != "Verbs" }
        .filter { it.isNotBlank() }
}

/** Whether a CLP command reported success; the iLO prints this tag for every command. */
fun commandSucceeded(output: String): Boolean =
    output.contains("COMMAND COMPLETED", ignoreCase = true) &&
        !output.contains("status=2", ignoreCase = true)
