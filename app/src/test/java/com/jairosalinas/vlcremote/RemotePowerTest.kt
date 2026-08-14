package com.jairosalinas.vlcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

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
    fun constantTimeFingerprintComparisonHasExpectedSemantics() {
        assertTrue(RemotePowerController.constantTimeEquals("SHA256:abc", "SHA256:abc"))
        assertFalse(RemotePowerController.constantTimeEquals("SHA256:abc", "SHA256:abd"))
    }

    private fun assertStableFingerprint(key: java.security.PublicKey) {
        val first = RemotePowerController.sha256Fingerprint(key)
        val second = RemotePowerController.sha256Fingerprint(key)

        assertTrue(first.startsWith("SHA256:"))
        assertFalse(first.substringAfter("SHA256:").contains('='))
        assertEquals(first, second)
    }
}
