package com.example.agentchat.data.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderErrorMapperTest {
    @Test fun mapsAuthenticationAndAvailabilityCodes() {
        assertEquals("unauthorized", ProviderErrorMapper.http(401).code)
        assertEquals("unauthorized", ProviderErrorMapper.http(403).code)
        assertEquals("rate_limited", ProviderErrorMapper.http(429).code)
        assertEquals("provider_unavailable", ProviderErrorMapper.http(503).code)
        assertEquals("timeout", ProviderErrorMapper.provider("timeout").code)
    }

    @Test fun mapsSchemaAndUnknownProviderErrorsSeparately() {
        assertEquals("protocol_error", ProviderErrorMapper.provider("invalid_schema").code)
        assertEquals("provider_error", ProviderErrorMapper.provider("unknown").code)
    }
}
