package com.wirekey.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleRemoteControlProtocolTest {

    @Test
    fun `only the exact versioned trigger command is accepted`() {
        assertTrue(BleRemoteControlProtocol.isTrigger(byteArrayOf(0x01, 0x01)))
        assertFalse(BleRemoteControlProtocol.isTrigger(null))
        assertFalse(BleRemoteControlProtocol.isTrigger(byteArrayOf()))
        assertFalse(BleRemoteControlProtocol.isTrigger(byteArrayOf(0x01)))
        assertFalse(BleRemoteControlProtocol.isTrigger(byteArrayOf(0x01, 0x02)))
        assertFalse(BleRemoteControlProtocol.isTrigger(byteArrayOf(0x02, 0x01)))
        assertFalse(BleRemoteControlProtocol.isTrigger(byteArrayOf(0x01, 0x01, 0x00)))
    }
}
