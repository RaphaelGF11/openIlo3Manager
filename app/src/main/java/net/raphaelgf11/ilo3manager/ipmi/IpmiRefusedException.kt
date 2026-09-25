package net.raphaelgf11.ilo3manager.ipmi

import java.io.IOException

/**
 * A refusal the BMC will give again: wrong credentials, unknown user, privilege denied.
 *
 * Told apart from a timeout because the answer actually arrived. Repeating the poll cannot change
 * it, and for a bad password every extra attempt counts against the iLO's lockout threshold — so
 * the retry loop lets this one through instead of hammering the account.
 */
class IpmiRefusedException(message: String) : IOException(message)
