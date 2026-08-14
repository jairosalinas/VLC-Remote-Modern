package com.jairosalinas.vlcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey

class RemotePowerTest {
    @Test
    fun linuxProfileUsesValidatedDetachedVlcCommand() {
        assertEquals(
            "export DISPLAY=:0\nnohup vlc >/dev/null 2>&1 </dev/null &",
            RemoteLaunchProfiles.linux.startCommand
        )
        assertEquals("pkill -TERM -x vlc", RemoteLaunchProfiles.linux.stopCommand)
        assertEquals("pgrep -x vlc >/dev/null", RemoteLaunchProfiles.linux.checkCommand)
        assertFalse(RemoteServerPlatform.LINUX.experimental)
    }

    @Test
    fun nonLinuxProfilesAreMarkedExperimental() {
        assertTrue(RemoteServerPlatform.WINDOWS.experimental)
        assertTrue(RemoteServerPlatform.MACOS.experimental)
        assertTrue(RemoteServerPlatform.CUSTOM.experimental)
    }

    @Test
    fun platformSelectionReturnsIndependentDefaults() {
        assertEquals(RemoteLaunchProfiles.linux, RemoteLaunchProfiles.forPlatform(RemoteServerPlatform.LINUX))
        assertEquals(RemoteLaunchProfiles.windows, RemoteLaunchProfiles.forPlatform(RemoteServerPlatform.WINDOWS))
        assertEquals(RemoteLaunchProfiles.macos, RemoteLaunchProfiles.forPlatform(RemoteServerPlatform.MACOS))
        assertEquals(RemoteLaunchProfiles.custom, RemoteLaunchProfiles.forPlatform(RemoteServerPlatform.CUSTOM))
        assertNotEquals(RemoteLaunchProfiles.linux.startCommand, RemoteLaunchProfiles.macos.startCommand)
    }

    @Test
    fun androidSshConfigNeverOffersCurve25519Kex() {
        val config = RemotePowerController.createAndroidCompatibleConfig(apiLevel = 36)
        val names = config.keyExchangeFactories.map { it.name }

        assertFalse(names.contains("curve25519-sha256"))
        assertFalse(names.contains("curve25519-sha256@libssh.org"))
        assertTrue(names.contains("ecdh-sha2-nistp256"))
        assertTrue(names.contains("diffie-hellman-group14-sha256"))
    }

    @Test
    fun preApi33AndroidSshConfigAvoidsEd25519HostKeys() {
        val config = RemotePowerController.createAndroidCompatibleConfig(apiLevel = 32)
        assertFalse(config.keyAlgorithms.any { it.name.contains("ed25519", ignoreCase = true) })
    }

    @Test
    fun modernAndroidSshConfigKeepsEd25519HostKeys() {
        val config = RemotePowerController.createAndroidCompatibleConfig(apiLevel = 36)
        assertTrue(config.keyAlgorithms.any { it.name.equals("ssh-ed25519", ignoreCase = true) })
    }

    @Test
    fun rsaFingerprintIsStableSha256Format() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val key = generator.generateKeyPair().public
        assertStableFingerprint(key)
    }

    @Test
    fun ed25519FingerprintIsStableSha256Format() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val key = generator.generateKeyPair().public
        assertStableFingerprint(key)
    }

    @Test
    fun androidConscryptOidEd25519FingerprintMatchesStandardEd25519() {
        val generator = KeyPairGenerator.getInstance("Ed25519")
        val standard = generator.generateKeyPair().public
        val androidStyle = AndroidOidEd25519PublicKey(standard)

        assertEquals(
            RemotePowerController.sha256Fingerprint(standard),
            RemotePowerController.sha256Fingerprint(androidStyle)
        )
        assertEquals(
            RemotePowerController.sshPublicKeyBlob(standard).toList(),
            RemotePowerController.sshPublicKeyBlob(androidStyle).toList()
        )
    }

    @Test
    fun constantTimeFingerprintComparisonHasExpectedSemantics() {
        assertTrue(RemotePowerController.constantTimeEquals("SHA256:abc", "SHA256:abc"))
        assertFalse(RemotePowerController.constantTimeEquals("SHA256:abc", "SHA256:abd"))
    }

    private fun assertStableFingerprint(key: PublicKey) {
        val first = RemotePowerController.sha256Fingerprint(key)
        val second = RemotePowerController.sha256Fingerprint(key)

        assertTrue(first.startsWith("SHA256:"))
        assertFalse(first.substringAfter("SHA256:").contains('='))
        assertEquals(first, second)
    }

    private class AndroidOidEd25519PublicKey(
        private val delegate: PublicKey
    ) : PublicKey {
        override fun getAlgorithm(): String = "1.3.101.112"
        override fun getFormat(): String = delegate.format
        override fun getEncoded(): ByteArray = delegate.encoded.clone()
    }
}
